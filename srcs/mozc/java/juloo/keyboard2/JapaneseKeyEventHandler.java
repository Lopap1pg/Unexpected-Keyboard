package juloo.keyboard2;

import android.content.Context;
import android.view.inputmethod.InputConnection;
import juloo.keyboard2.ime.JapaneseIme;
import juloo.keyboard2.suggestions.Suggestions;

/**
 * Thin routing layer that leaves the existing KeyEventHandler untouched.
 * In A mode every event is delegated verbatim. In あ mode text/editing keys
 * are offered to Mozc first and fall back to the original handler when Mozc
 * does not consume them.
 */
public final class JapaneseKeyEventHandler implements Config.IKeyEventHandler
{
  private static final String TOGGLE_TEXT = "A/あ";

  private final KeyEventHandler _base;
  private final KeyEventHandler.IReceiver _receiver;
  private final JapaneseIme _japanese;
  private final Runnable _mode_changed;

  public JapaneseKeyEventHandler(Context context, KeyEventHandler base,
      Suggestions suggestions, KeyEventHandler.IReceiver receiver,
      Runnable modeChanged)
  {
    _base = base;
    _receiver = receiver;
    _japanese = new JapaneseIme(context, suggestions);
    _mode_changed = modeChanged;
  }

  public void started()
  {
    _japanese.started(_receiver.getCurrentInputConnection());
  }

  public boolean is_japanese_enabled()
  {
    return _japanese.is_enabled();
  }

  private static boolean is_toggle(KeyValue key)
  {
    return key != null
      && key.getKind() == KeyValue.Kind.String
      && TOGGLE_TEXT.equals(key.getString());
  }

  @Override
  public void key_down(KeyValue key, boolean isSwipe)
  {
    if (is_toggle(key))
      return;
    _base.key_down(key, isSwipe);
  }

  @Override
  public void key_up(KeyValue key, Pointers.Modifiers mods)
  {
    if (key == null)
      return;
    InputConnection conn = _receiver.getCurrentInputConnection();

    if (is_toggle(key))
    {
      if (_japanese.toggle(conn) && _mode_changed != null)
        _mode_changed.run();
      return;
    }

    if (_japanese.is_enabled())
    {
      switch (key.getKind())
      {
        case Char:
          if (_japanese.send_text(String.valueOf(key.getChar()), conn))
            return;
          break;
        case String:
          if (_japanese.send_text(key.getString(), conn))
            return;
          break;
        case Keyevent:
          if (_japanese.send_keycode(key.getKeyevent(), conn))
            return;
          break;
        case Editing:
          switch (key.getEditing())
          {
            case SPACE_BAR:
              if (_japanese.send_space(conn))
                return;
              break;
            case BACKSPACE:
              if (_japanese.send_backspace(conn))
                return;
              break;
            default:
              break;
          }
          break;
        default:
          break;
      }
    }

    _base.key_up(key, mods);
  }

  @Override
  public void mods_changed(Pointers.Modifiers mods)
  {
    _base.mods_changed(mods);
  }

  @Override
  public void suggestion_entered(String text)
  {
    if (_japanese.suggestion_entered(
          text, _receiver.getCurrentInputConnection()))
      return;
    _base.suggestion_entered(text);
  }
}
