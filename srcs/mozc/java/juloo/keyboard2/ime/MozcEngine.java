package juloo.keyboard2.ime;

import android.content.Context;
import android.util.Log;
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJni;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig;

/**
 * Small Java facade around upstream Mozc's Android JNI API.
 *
 * The native side owns one process-wide SessionHandler. This class serializes
 * access to it and owns one Mozc session used by Unexpected Keyboard.
 */
public final class MozcEngine
{
  private static final String TAG = "UnexpectedMozc";
  private static final long INVALID_SESSION_ID = 0L;
  private static final String DATA_ASSET = "mozc.data";

  private static volatile MozcEngine _instance;

  private final Object _lock = new Object();
  private long _session_id = INVALID_SESSION_ID;

  private MozcEngine() {}

  /** Load libmozc.so and mozc.data once for this process. */
  public static MozcEngine get(Context context)
  {
    if (_instance != null)
      return _instance;
    synchronized (MozcEngine.class)
    {
      if (_instance != null)
        return _instance;
      if (context == null)
        return null;
      Context app = context.getApplicationContext();
      try
      {
        System.loadLibrary("mozc");
        if (!MozcJni.initialize())
        {
          Log.e(TAG, "MozcJni.initialize() failed");
          return null;
        }
        File profile = new File(app.getFilesDir(), "mozc");
        if (!profile.exists() && !profile.mkdirs())
          Log.w(TAG, "Could not create Mozc profile directory");
        File data = extract_data(app);
        if (data == null || !MozcJni.onPostLoad(
              profile.getAbsolutePath(), data.getAbsolutePath()))
        {
          Log.e(TAG, "MozcJni.onPostLoad() failed");
          return null;
        }
      }
      catch (Throwable e)
      {
        Log.e(TAG, "Unable to initialize Mozc", e);
        return null;
      }
      _instance = new MozcEngine();
      return _instance;
    }
  }

  /** Create/reuse the client session. */
  public boolean ensure_session()
  {
    synchronized (_lock)
    {
      if (_session_id != INVALID_SESSION_ID)
        return true;
      ProtoCommands.Output out = eval_locked(
          ProtoCommands.Input.newBuilder()
            .setType(ProtoCommands.Input.CommandType.CREATE_SESSION));
      if (out == null || !out.hasId())
        return false;
      _session_id = out.getId();
      return true;
    }
  }

  public void close_session()
  {
    synchronized (_lock)
    {
      if (_session_id == INVALID_SESSION_ID)
        return;
      eval_locked(ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.DELETE_SESSION)
          .setId(_session_id));
      _session_id = INVALID_SESSION_ID;
    }
  }

  public ProtoCommands.Output send_key(
      ProtoCommands.KeyEvent key, ProtoCommands.Context context)
  {
    synchronized (_lock)
    {
      if (!ensure_session_locked())
        return null;
      ProtoCommands.Input.Builder input = ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.SEND_KEY)
          .setId(_session_id)
          .setKey(key);
      if (context != null)
        input.setContext(context);
      return eval_locked(input);
    }
  }

  public ProtoCommands.Output send_command(ProtoCommands.SessionCommand command)
  {
    synchronized (_lock)
    {
      if (!ensure_session_locked())
        return null;
      return eval_locked(ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.SEND_COMMAND)
          .setId(_session_id)
          .setCommand(command));
    }
  }

  /** SET_REQUEST is handled by SessionHandler and does not need a session id. */
  public ProtoCommands.Output set_request(ProtoCommands.Request request)
  {
    synchronized (_lock)
    {
      return eval_locked(ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.SET_REQUEST)
          .setRequest(request));
    }
  }

  public ProtoConfig.Config get_config()
  {
    synchronized (_lock)
    {
      ProtoCommands.Output out = eval_locked(ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.GET_CONFIG));
      return out != null && out.hasConfig() ? out.getConfig() : null;
    }
  }

  public ProtoCommands.Output set_config(ProtoConfig.Config config)
  {
    synchronized (_lock)
    {
      return eval_locked(ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.SET_CONFIG)
          .setConfig(config));
    }
  }

  public ProtoCommands.Output eval(ProtoCommands.Input.Builder input)
  {
    synchronized (_lock)
    {
      return eval_locked(input);
    }
  }

  private boolean ensure_session_locked()
  {
    if (_session_id != INVALID_SESSION_ID)
      return true;
    ProtoCommands.Output out = eval_locked(
        ProtoCommands.Input.newBuilder()
          .setType(ProtoCommands.Input.CommandType.CREATE_SESSION));
    if (out == null || !out.hasId())
      return false;
    _session_id = out.getId();
    return true;
  }

  private ProtoCommands.Output eval_locked(ProtoCommands.Input.Builder input)
  {
    try
    {
      ProtoCommands.Command command = ProtoCommands.Command.newBuilder()
          .setInput(input).build();
      byte[] response = MozcJni.evalCommand(command.toByteArray());
      if (response == null)
        return null;
      return ProtoCommands.Command.parseFrom(response).getOutput();
    }
    catch (Throwable e)
    {
      Log.e(TAG, "Mozc command failed: " + input.getType(), e);
      return null;
    }
  }

  private static File extract_data(Context context)
  {
    File target = new File(context.getFilesDir(), DATA_ASSET);
    File stamp = new File(context.getFilesDir(), DATA_ASSET + ".stamp");
    try
    {
      String version = Long.toString(context.getPackageManager()
          .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
      if (target.isFile() && stamp.isFile()
          && version.equals(read_small_file(stamp)))
        return target;

      try (InputStream in = context.getAssets().open(DATA_ASSET);
           FileOutputStream out = new FileOutputStream(target))
      {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1)
          out.write(buf, 0, n);
      }
      try (FileOutputStream out = new FileOutputStream(stamp))
      {
        out.write(version.getBytes("UTF-8"));
      }
      return target;
    }
    catch (Throwable e)
    {
      Log.e(TAG, "Unable to extract mozc.data", e);
      return null;
    }
  }

  private static String read_small_file(File file) throws Exception
  {
    try (InputStream in = new java.io.FileInputStream(file))
    {
      byte[] bytes = new byte[(int)Math.min(file.length(), 128)];
      int n = in.read(bytes);
      if (n < 0)
        return "";
      return new String(bytes, 0, n, "UTF-8");
    }
  }
}
