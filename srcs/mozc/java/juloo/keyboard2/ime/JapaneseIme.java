package juloo.keyboard2.ime;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import java.util.ArrayList;
import juloo.keyboard2.suggestions.Suggestions;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands;
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig;

/**
 * PC-style Japanese input mode for Unexpected Keyboard.
 *
 * The visible keyboard remains the normal QWERTY layout. ASCII key presses are
 * sent to Mozc in HIRAGANA composition mode, so Mozc performs romaji -> kana,
 * conversion, prediction and learning. Android only sees Mozc's preedit/result.
 */
public final class JapaneseIme
{
  private final MozcEngine _engine;
  private final Suggestions _suggestions;
  private boolean _enabled = false;
  private boolean _configured = false;
  private boolean _had_preedit = false;
  private final ArrayList<Candidate> _candidates = new ArrayList<Candidate>();

  static final class Candidate
  {
    final int id;
    final String text;
    Candidate(int i, String t) { id = i; text = t; }
  }

  public JapaneseIme(Context context, Suggestions suggestions)
  {
    _engine = MozcEngine.get(context);
    _suggestions = suggestions;
  }

  public boolean is_available() { return _engine != null; }
  public boolean is_enabled() { return _enabled && _engine != null; }
  public boolean has_composition() { return _had_preedit; }

  /** Reset per-editor context while keeping the user's A/あ mode. */
  public void started(InputConnection conn)
  {
    clear_candidates();
    _had_preedit = false;
    if (_engine == null)
      return;
    _engine.ensure_session();
    reset_context();
    if (_enabled)
      configure_japanese();
    if (conn != null)
      conn.finishComposingText();
  }

  /** Toggle between direct Latin input and Mozc-backed Japanese input. */
  public boolean toggle(InputConnection conn)
  {
    if (_engine == null)
      return false;
    if (_enabled)
    {
      ProtoCommands.Output out = _engine.send_command(
          ProtoCommands.SessionCommand.newBuilder()
            .setType(ProtoCommands.SessionCommand.CommandType.SUBMIT)
            .build());
      render(out, conn);
      if (conn != null)
        conn.finishComposingText();
      _had_preedit = false;
      clear_candidates();
      _enabled = false;
    }
    else
    {
      _enabled = true;
      configure_japanese();
      reset_context();
    }
    return true;
  }

  /** Send printable text. Returns true when Mozc consumed the key. */
  public boolean send_text(String text, InputConnection conn)
  {
    if (!is_enabled() || text == null || text.length() == 0)
      return false;
    ProtoCommands.KeyEvent.Builder key = ProtoCommands.KeyEvent.newBuilder();
    if (text.length() == 1 && text.charAt(0) >= 0x20 && text.charAt(0) <= 0x7E)
      key.setKeyCode(text.charAt(0));
    else
      key.setKeyString(text);
    return send_key(key.build(), conn);
  }

  public boolean send_space(InputConnection conn)
  {
    return send_special(ProtoCommands.KeyEvent.SpecialKey.SPACE, conn);
  }

  public boolean send_backspace(InputConnection conn)
  {
    return send_special(ProtoCommands.KeyEvent.SpecialKey.BACKSPACE, conn);
  }

  public boolean send_keycode(int keyCode, InputConnection conn)
  {
    if (!is_enabled())
      return false;
    ProtoCommands.KeyEvent.SpecialKey special;
    switch (keyCode)
    {
      case KeyEvent.KEYCODE_ENTER: special = ProtoCommands.KeyEvent.SpecialKey.ENTER; break;
      case KeyEvent.KEYCODE_DPAD_LEFT: special = ProtoCommands.KeyEvent.SpecialKey.LEFT; break;
      case KeyEvent.KEYCODE_DPAD_RIGHT: special = ProtoCommands.KeyEvent.SpecialKey.RIGHT; break;
      case KeyEvent.KEYCODE_DPAD_UP: special = ProtoCommands.KeyEvent.SpecialKey.UP; break;
      case KeyEvent.KEYCODE_DPAD_DOWN: special = ProtoCommands.KeyEvent.SpecialKey.DOWN; break;
      case KeyEvent.KEYCODE_ESCAPE: special = ProtoCommands.KeyEvent.SpecialKey.ESCAPE; break;
      case KeyEvent.KEYCODE_DEL: special = ProtoCommands.KeyEvent.SpecialKey.BACKSPACE; break;
      case KeyEvent.KEYCODE_FORWARD_DEL: special = ProtoCommands.KeyEvent.SpecialKey.DEL; break;
      case KeyEvent.KEYCODE_TAB: special = ProtoCommands.KeyEvent.SpecialKey.TAB; break;
      default: return false;
    }
    return send_special(special, conn);
  }

  /** Submit the candidate that was tapped in Unexpected Keyboard's strip. */
  public boolean suggestion_entered(String text, InputConnection conn)
  {
    if (!is_enabled() || text == null)
      return false;
    for (Candidate candidate : _candidates)
    {
      if (text.equals(candidate.text))
      {
        ProtoCommands.Output out = _engine.send_command(
            ProtoCommands.SessionCommand.newBuilder()
              .setType(ProtoCommands.SessionCommand.CommandType.SUBMIT_CANDIDATE)
              .setId(candidate.id)
              .build());
        render(out, conn);
        return true;
      }
    }
    return false;
  }

  public void reset_context()
  {
    if (_engine == null)
      return;
    _engine.send_command(ProtoCommands.SessionCommand.newBuilder()
        .setType(ProtoCommands.SessionCommand.CommandType.RESET_CONTEXT)
        .build());
  }

  private boolean send_special(
      ProtoCommands.KeyEvent.SpecialKey special, InputConnection conn)
  {
    if (!is_enabled())
      return false;
    return send_key(ProtoCommands.KeyEvent.newBuilder()
        .setSpecialKey(special).build(), conn);
  }

  private boolean send_key(ProtoCommands.KeyEvent key, InputConnection conn)
  {
    ProtoCommands.Output out = _engine.send_key(key, make_context(conn));
    if (out == null)
      return false;
    render(out, conn);
    return out.hasConsumed() && out.getConsumed();
  }

  private ProtoCommands.Context make_context(InputConnection conn)
  {
    if (conn == null)
      return null;
    ProtoCommands.Context.Builder context = ProtoCommands.Context.newBuilder();
    CharSequence before = conn.getTextBeforeCursor(128, 0);
    if (before != null && before.length() != 0)
      context.setPrecedingText(before.toString());
    return context.build();
  }

  /** Apply mobile-friendly request settings and switch Mozc to hiragana mode. */
  private void configure_japanese()
  {
    if (_engine == null || !_engine.ensure_session())
      return;

    ProtoCommands.Request request = ProtoCommands.Request.newBuilder()
        .setSpecialRomanjiTable(ProtoCommands.Request.SpecialRomanjiTable.DEFAULT_TABLE)
        .setZeroQuerySuggestion(true)
        .setMixedConversion(true)
        .setUpdateInputModeFromSurroundingText(false)
        .setAutoPartialSuggestion(true)
        .setCandidatePageSize(36)
        .setSpaceOnAlphanumeric(
            ProtoCommands.Request.SpaceOnAlphanumeric.SPACE_OR_CONVERT_COMMITTING_COMPOSITION)
        .setCrossingEdgeBehavior(
            ProtoCommands.Request.CrossingEdgeBehavior.COMMIT_WITHOUT_CONSUMING)
        .setKanaModifierInsensitiveConversion(true)
        .build();
    _engine.set_request(request);

    if (!_configured)
    {
      ProtoConfig.Config config = _engine.get_config();
      ProtoConfig.Config.Builder builder = config == null
          ? ProtoConfig.Config.newBuilder() : config.toBuilder();
      builder.setUseKanaModifierInsensitiveConversion(true);
      builder.setUseTypingCorrection(true);
      _engine.set_config(builder.build());
      _configured = true;
    }

    _engine.send_command(ProtoCommands.SessionCommand.newBuilder()
        .setType(ProtoCommands.SessionCommand.CommandType.SWITCH_COMPOSITION_MODE)
        .setCompositionMode(ProtoCommands.CompositionMode.HIRAGANA)
        .build());
  }

  /** Reflect a Mozc output in Android's composing region and candidate strip. */
  private void render(ProtoCommands.Output out, InputConnection conn)
  {
    if (out == null || conn == null)
      return;

    final boolean hadPreedit = _had_preedit;
    final boolean hasResult = out.hasResult()
        && out.getResult().getValue().length() != 0;
    String preedit = null;
    int preeditCursor = 0;
    if (out.hasPreedit())
    {
      StringBuilder text = new StringBuilder();
      for (ProtoCommands.Preedit.Segment segment : out.getPreedit().getSegmentList())
        text.append(segment.getValue());
      preedit = text.toString();
      preeditCursor = Math.max(0,
          Math.min(out.getPreedit().getCursor(), preedit.length()));
    }

    conn.beginBatchEdit();
    try
    {
      if (out.hasDeletionRange())
      {
        int left = -out.getDeletionRange().getOffset();
        int right = out.getDeletionRange().getLength() - left;
        if (left >= 0 && right >= 0)
          conn.deleteSurroundingText(left, right);
      }

      if (hasResult)
        conn.commitText(out.getResult().getValue(), 1);

      if (preedit != null)
      {
        if (preedit.length() == 0)
        {
          // If Backspace removed the last composing character, finishing the
          // span would confirm the stale character. An empty commit replaces
          // that span with nothing. A real result already replaced it above.
          if (hadPreedit && !hasResult)
            conn.commitText("", 1);
          else
            conn.finishComposingText();
          _had_preedit = false;
        }
        else
        {
          conn.setComposingText(preedit, 1);
          _had_preedit = true;
        }
      }
      else if (hadPreedit && hasResult)
      {
        conn.finishComposingText();
        _had_preedit = false;
      }
    }
    finally
    {
      conn.endBatchEdit();
    }

    // setComposingText can place the Android caret only before/after the span.
    // When Mozc moved inside the composition with an arrow key, reposition it
    // explicitly after the batch edit so the visible caret follows Mozc.
    if (preedit != null && preedit.length() != 0
        && preeditCursor < preedit.length())
      place_composing_cursor(conn, preedit.length(), preeditCursor);

    update_candidates(out, _had_preedit);
  }

  private void place_composing_cursor(
      InputConnection conn, int preeditLength, int cursor)
  {
    ExtractedText extracted = conn.getExtractedText(new ExtractedTextRequest(), 0);
    if (extracted == null)
      return;
    int end = extracted.startOffset + extracted.selectionStart;
    int start = end - preeditLength;
    if (start < 0)
      return;
    int target = start + cursor;
    conn.setSelection(target, target);
  }

  private void update_candidates(ProtoCommands.Output out, boolean active)
  {
    _candidates.clear();
    ArrayList<String> texts = new ArrayList<String>();
    if (out != null && out.hasCandidateWindow())
    {
      ProtoCandidateWindow.CandidateWindow window = out.getCandidateWindow();
      for (ProtoCandidateWindow.CandidateWindow.Candidate candidate
          : window.getCandidateList())
      {
        if (!candidate.hasId())
          continue;
        _candidates.add(new Candidate(candidate.getId(), candidate.getValue()));
        texts.add(candidate.getValue());
      }
    }
    else if (out != null && out.hasAllCandidateWords())
    {
      for (ProtoCandidateWindow.CandidateWord candidate
          : out.getAllCandidateWords().getCandidatesList())
      {
        if (!candidate.hasId())
          continue;
        _candidates.add(new Candidate(candidate.getId(), candidate.getValue()));
        texts.add(candidate.getValue());
      }
    }
    _suggestions.composition_candidates(texts, active);
  }

  private void clear_candidates()
  {
    _candidates.clear();
    _suggestions.composition_candidates(new ArrayList<String>(), false);
  }
}
