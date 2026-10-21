package com.google.android.apps.inputmethod.libs.mozc.session;

/** JNI entry points exported by upstream Mozc's android/jni/mozcjni.cc. */
public final class MozcJni
{
  private MozcJni() {}

  /** Register the native methods below. Call after System.loadLibrary("mozc"). */
  public static native boolean initialize();

  /** Evaluate a serialized mozc.commands.Command and return the serialized result. */
  public static native byte[] evalCommand(byte[] command);

  /** Initialize the process-wide Mozc session handler. */
  public static native boolean onPostLoad(
      String userProfileDirectoryPath, String dataFilePath);

  /** Return the data version of the loaded mozc.data file. */
  public static native String getDataVersion();
}
