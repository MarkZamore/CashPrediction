package ru.cashprediction.core.ui.json;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.menu.MenuNode;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;

/**
 * Эффект web-протокола (архитектура §5): то, что {@code WebUiPort} записывает в журнал эффектов с монотонным
 * {@code seq} и отдаёт в ответе {@code intent} и в long-poll {@code events}. Поле {@code type} JSON равно {@link #type()}.
 * Гарантии: эффекты упорядочены по {@code seq}; ревизии форм монотонны; поле в фокусе не перезаписывается своим эхом.
 *
 * <p><b>Эффекты одной вкладки.</b> У {@link ContextMenu} и {@link Reload} есть поле {@code tab}: их выполняет только
 * вкладка с этим id (та, что нажала клавишу или прислала ошибку JavaScript), остальные вкладки их пропускают. Решение
 * о том, что делать, всегда принимает ядро (правило R2), вкладка только исполняет эффект.</p>
 */
public sealed interface WebEffect permits WebEffect.Screen, WebEffect.FormOpen, WebEffect.FormViewUpdate,
        WebEffect.FormClose, WebEffect.FormFront, WebEffect.AlertOpen, WebEffect.AlertUpdate, WebEffect.AlertClose,
        WebEffect.ContextMenu, WebEffect.Focus, WebEffect.Reveal, WebEffect.Clipboard, WebEffect.Inert,
        WebEffect.Reload, WebEffect.Exit, WebEffect.TestStep {

    /** @return значение поля {@code type} в JSON */
    String type();

    /**
     * {@code screen{revision, parts}}: изменившиеся части главного окна (таблица — без строк, строки по запросу {@code rows}).
     *
     * @param model модель экрана
     * @param parts изменившиеся части
     */
    record Screen(MainScreenModel model, EnumSet<ScreenPart> parts) implements WebEffect {
        /** Копирует множество частей. */
        public Screen {
            parts = EnumSet.copyOf(parts);
        }

        @Override
        public String type() {
            return "screen";
        }
    }

    /**
     * {@code form.open{window:{id, ownerId, modal, placement, spec, view}}}.
     *
     * @param windowId  id окна
     * @param ownerId   владелец
     * @param modal     модальное ли ({@code showModal})
     * @param placement положение
     * @param spec      раскладка
     * @param view      начальная модель
     */
    record FormOpen(String windowId, String ownerId, boolean modal, Placement placement, FormSpec spec, FormView view)
            implements WebEffect {
        @Override
        public String type() {
            return "form.open";
        }
    }

    /**
     * {@code form.view{windowId, view, echoOf:{tab, clientRev}}}.
     *
     * @param windowId      id окна
     * @param view          модель
     * @param echoTab       вкладка, чья правка породила модель, или пустая строка
     * @param echoClientRev номер правки этой вкладки или 0
     */
    record FormViewUpdate(String windowId, FormView view, String echoTab, long echoClientRev) implements WebEffect {
        @Override
        public String type() {
            return "form.view";
        }
    }

    /**
     * {@code form.close{windowId}}.
     *
     * @param windowId id окна
     */
    record FormClose(String windowId) implements WebEffect {
        @Override
        public String type() {
            return "form.close";
        }
    }

    /**
     * {@code form.front{windowId}}.
     *
     * @param windowId id окна
     */
    record FormFront(String windowId) implements WebEffect {
        @Override
        public String type() {
            return "form.front";
        }
    }

    /**
     * {@code alert.open{alertId, spec}}.
     *
     * @param alertId id сообщения
     * @param spec    описание
     */
    record AlertOpen(String alertId, AlertSpec spec) implements WebEffect {
        @Override
        public String type() {
            return "alert.open";
        }
    }

    /**
     * {@code alert.update{alertId, spec}}.
     *
     * @param alertId id сообщения
     * @param spec    новое описание
     */
    record AlertUpdate(String alertId, AlertSpec spec) implements WebEffect {
        @Override
        public String type() {
            return "alert.update";
        }
    }

    /**
     * {@code alert.close{alertId}} (в том числе другим вкладкам после первого ответа).
     *
     * @param alertId id сообщения
     */
    record AlertClose(String alertId) implements WebEffect {
        @Override
        public String type() {
            return "alert.close";
        }
    }

    /**
     * {@code contextMenu{tab, target, items}}: контекстное меню, открытое с клавиатуры (Shift+F10 / Menu,
     * {@code UiPort.showContextMenu}). Положение считает вкладка по правилам {@code UiPort.showContextMenu}; выбранный
     * пункт возвращается намерением {@code command} с источником {@code CONTEXT_MENU}.
     *
     * @param tab    вкладка, в которой нажата клавиша
     * @param target объект меню
     * @param items  пункты
     */
    record ContextMenu(String tab, ContextTarget target, List<MenuNode> items) implements WebEffect {
        /** Проверяет поля и копирует список. */
        public ContextMenu {
            tab = Objects.requireNonNullElse(tab, "");
            Objects.requireNonNull(target, "target");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        @Override
        public String type() {
            return "contextMenu";
        }
    }

    /**
     * {@code reload{tab}}: перезагрузить страницу. Ядро отправляет его после кнопки «Перезагрузить страницу»
     * сообщения об ошибке JavaScript (§6.33 [WEB], §10 №10), чтобы вкладка не решала сама по id кнопки.
     *
     * @param tab вкладка, в которой произошла ошибка
     */
    record Reload(String tab) implements WebEffect {
        /** Заменяет {@code null}. */
        public Reload {
            tab = Objects.requireNonNullElse(tab, "");
        }

        @Override
        public String type() {
            return "reload";
        }
    }

    /**
     * {@code focus{target}}.
     *
     * @param target цель
     */
    record Focus(FocusTarget target) implements WebEffect {
        @Override
        public String type() {
            return "focus";
        }
    }

    /**
     * {@code reveal{rowId, mode}}.
     *
     * @param rowId id строки
     * @param mode  режим
     */
    record Reveal(String rowId, RevealMode mode) implements WebEffect {
        @Override
        public String type() {
            return "reveal";
        }
    }

    /**
     * {@code clipboard{text}} (приходит в ответе на намерение, внутри жеста пользователя).
     *
     * @param text текст
     */
    record Clipboard(String text) implements WebEffect {
        @Override
        public String type() {
            return "clipboard";
        }
    }

    /**
     * {@code inert{value}}: страница неактивна (ожидание решения о восстановлении).
     *
     * @param value неактивна ли
     */
    record Inert(boolean value) implements WebEffect {
        @Override
        public String type() {
            return "inert";
        }
    }

    /**
     * {@code exit{kind, title, text}}: экран §6.30.
     *
     * @param kind  вид завершения ({@code WEB_STOPPED}/{@code WEB_CRASHED})
     * @param title заголовок экрана
     * @param text  текст экрана
     */
    record Exit(ExitKind kind, String title, String text) implements WebEffect {
        @Override
        public String type() {
            return "exit";
        }
    }

    /**
     * {@code test.step{n, command}} (только с {@code --test-api}): шаг сценария для тестового драйвера вкладки.
     *
     * @param n       номер шага
     * @param command строка команды сценария
     */
    record TestStep(int n, String command) implements WebEffect {
        @Override
        public String type() {
            return "test.step";
        }
    }
}
