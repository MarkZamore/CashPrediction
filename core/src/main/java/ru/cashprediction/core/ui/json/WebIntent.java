package ru.cashprediction.core.ui.json;

import java.util.Objects;
import ru.cashprediction.core.app.Activation;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.FocusScope;
import ru.cashprediction.core.ui.command.InvokeSource;
import ru.cashprediction.core.ui.command.KeyChord;

/**
 * Намерение web-протокола {@code POST /api/ui/intent {tab, afterSeq, intent:{type, …}}} (архитектура §5). Каждый тип
 * один к одному отображается на {@code UiIntents}, {@code FormSession} или ответ на сообщение. Поле {@code type} JSON
 * равно {@link #type()}.
 */
public sealed interface WebIntent permits WebIntent.Command, WebIntent.Key, WebIntent.SelectRow, WebIntent.ActivateRow,
        WebIntent.FilterText, WebIntent.SliderCommit, WebIntent.SpinnerCommit, WebIntent.MainGeometry,
        WebIntent.MenuHover, WebIntent.CloseMain, WebIntent.FormField, WebIntent.FormButton, WebIntent.FormPreview,
        WebIntent.FormActivate, WebIntent.FormSubmit, WebIntent.FormBounds, WebIntent.FormShown, WebIntent.FormClose,
        WebIntent.AlertAnswer, WebIntent.ClientError {

    /** @return значение поля {@code type} в JSON */
    String type();

    /**
     * {@code command{command, args, source}} → {@code UiIntents.command}. В JSON {@code command} — id спецификации
     * ({@code CommandId.id()}, например {@code file.save}), см. {@code UiJson}.
     *
     * @param command команда
     * @param args    аргументы (для {@code InvokeSource.FORM} в {@code key} — id окна формы)
     * @param source  источник: {@code MENU}, {@code TOOLBAR}, {@code CONTEXT_MENU}, {@code HOTKEY}, {@code MAIN},
     *                {@code FORM} (контекстное меню внутри формы); {@code SELFTEST} браузер не отправляет
     */
    record Command(CommandId command, CommandArgs args, InvokeSource source) implements WebIntent {
        /** Проверяет поля. */
        public Command {
            Objects.requireNonNull(command, "command");
            args = args == null ? CommandArgs.NONE : args;
            Objects.requireNonNull(source, "source");
        }

        @Override
        public String type() {
            return "command";
        }
    }

    /**
     * {@code key{chord, scope, focusId}} → {@code UiIntents.key}.
     *
     * @param chord   сочетание
     * @param scope   область фокуса
     * @param focusId уточнение фокуса (id карточки для {@code CARD}, id окна для {@code TEXT_INPUT} и {@code POPUP})
     *                или пустая строка
     */
    record Key(KeyChord chord, FocusScope scope, String focusId) implements WebIntent {
        /** Заменяет {@code null}. */
        public Key {
            focusId = Objects.requireNonNullElse(focusId, "");
        }

        @Override
        public String type() {
            return "key";
        }
    }

    /**
     * {@code selectRow{rowId}}.
     *
     * @param rowId id строки
     */
    record SelectRow(String rowId) implements WebIntent {
        @Override
        public String type() {
            return "selectRow";
        }
    }

    /**
     * {@code activateRow{rowId, columnId, how}}.
     *
     * @param rowId    id строки
     * @param columnId id колонки
     * @param how      щелчок
     */
    record ActivateRow(String rowId, String columnId, Activation how) implements WebIntent {
        @Override
        public String type() {
            return "activateRow";
        }
    }

    /**
     * {@code filterText{text}}.
     *
     * @param text текст фильтра
     */
    record FilterText(String text) implements WebIntent {
        @Override
        public String type() {
            return "filterText";
        }
    }

    /**
     * {@code sliderCommit{itemId, value}}.
     *
     * @param itemId id узла меню
     * @param value  значение
     */
    record SliderCommit(String itemId, int value) implements WebIntent {
        @Override
        public String type() {
            return "sliderCommit";
        }
    }

    /**
     * {@code spinnerCommit{itemId, value}}.
     *
     * @param itemId id узла меню
     * @param value  значение
     */
    record SpinnerCommit(String itemId, long value) implements WebIntent {
        @Override
        public String type() {
            return "spinnerCommit";
        }
    }

    /**
     * {@code mainGeometry{bounds, maximized}}.
     *
     * @param bounds    границы или {@code null}
     * @param maximized развёрнуто ли
     */
    record MainGeometry(WindowBounds bounds, boolean maximized) implements WebIntent {
        @Override
        public String type() {
            return "mainGeometry";
        }
    }

    /**
     * {@code menuHover{itemId}} ({@code itemId = null} — указатель ушёл с меню).
     *
     * @param itemId id узла или {@code null}
     */
    record MenuHover(String itemId) implements WebIntent {
        @Override
        public String type() {
            return "menuHover";
        }
    }

    /** {@code closeMain} → {@code UiIntents.closeMainRequested}. */
    record CloseMain() implements WebIntent {
        @Override
        public String type() {
            return "closeMain";
        }
    }

    /**
     * {@code formField{windowId, fieldId, raw, committed, clientRev}} → {@code FormSession.fieldChanged}.
     *
     * @param windowId  id окна
     * @param fieldId   id поля
     * @param raw       текст виджета
     * @param committed потеря фокуса или выбор
     * @param clientRev номер правки вкладки
     */
    record FormField(String windowId, String fieldId, String raw, boolean committed, long clientRev) implements WebIntent {
        @Override
        public String type() {
            return "formField";
        }
    }

    /**
     * {@code formButton{windowId, buttonId}}.
     *
     * @param windowId id окна
     * @param buttonId id кнопки
     */
    record FormButton(String windowId, String buttonId) implements WebIntent {
        @Override
        public String type() {
            return "formButton";
        }
    }

    /**
     * {@code formPreview{windowId, index, activated}}.
     *
     * @param windowId  id окна
     * @param index     номер элемента
     * @param activated двойной щелчок или пункт меню
     */
    record FormPreview(String windowId, int index, boolean activated) implements WebIntent {
        @Override
        public String type() {
            return "formPreview";
        }
    }

    /**
     * {@code formActivate{windowId, fieldId, index}} → {@code FormSession.fieldActivated}: двойной щелчок или Enter по
     * элементу списка (открыть план §6.10, папка или файл в «Выборе файла» §6.21).
     *
     * @param windowId id окна
     * @param fieldId  id поля-списка
     * @param index    номер элемента
     */
    record FormActivate(String windowId, String fieldId, int index) implements WebIntent {
        @Override
        public String type() {
            return "formActivate";
        }
    }

    /**
     * {@code formSubmit{windowId, fieldId}} → {@code FormSession.fieldSubmitted}: Enter в однострочном поле (перед ним
     * вкладка отправляет {@code formField} с {@code committed = true}). У всплывающего окна быстрой правки без кнопок
     * Enter идёт тем же путём.
     *
     * @param windowId id окна
     * @param fieldId  id поля
     */
    record FormSubmit(String windowId, String fieldId) implements WebIntent {
        @Override
        public String type() {
            return "formSubmit";
        }
    }

    /**
     * {@code formBounds{windowId, bounds}}.
     *
     * @param windowId id окна
     * @param bounds   границы
     */
    record FormBounds(String windowId, WindowBounds bounds) implements WebIntent {
        @Override
        public String type() {
            return "formBounds";
        }
    }

    /**
     * {@code formShown{windowId}}.
     *
     * @param windowId id окна
     */
    record FormShown(String windowId) implements WebIntent {
        @Override
        public String type() {
            return "formShown";
        }
    }

    /**
     * {@code formClose{windowId}} (крестик или Esc) → {@code FormSession.closeRequested}.
     *
     * @param windowId id окна
     */
    record FormClose(String windowId) implements WebIntent {
        @Override
        public String type() {
            return "formClose";
        }
    }

    /**
     * {@code alertButton{alertId, buttonId}}; первый ответ побеждает, остальным вкладкам уходит {@code alert.close}.
     *
     * @param alertId  id сообщения
     * @param buttonId id кнопки
     */
    record AlertAnswer(String alertId, String buttonId) implements WebIntent {
        @Override
        public String type() {
            return "alertButton";
        }
    }

    /**
     * {@code clientError{message, stack}}: ошибка JavaScript во вкладке (§6.33 [WEB]).
     *
     * @param message сообщение
     * @param stack   стек браузера
     */
    record ClientError(String message, String stack) implements WebIntent {
        @Override
        public String type() {
            return "clientError";
        }
    }
}
