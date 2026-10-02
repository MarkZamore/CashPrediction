package ru.cashprediction.fx.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет комментарии соответствия у конструкторов обязательных классов. */
class MappingCommentTest {
    /** Проверяет каждое место создания классов меню, сообщений и всплывающих окон. */
    @Test void constructorsExplainClientMapping() throws Exception {
        var required = Set.of("MenuBar", "Menu", "MenuItem", "CheckMenuItem", "RadioMenuItem", "SeparatorMenuItem", "CustomMenuItem", "MenuButton", "SplitMenuButton", "PopupWindow", "Popup", "PopupControl", "Tooltip", "ContextMenu", "ContextMenuEvent", "Dialog", "DialogPane", "ButtonType", "Alert", "TextInputDialog", "ChoiceDialog", "FileChooser", "DirectoryChooser");
        Path root = Path.of(System.getProperty("fx.basedir"), "src/main/java/ru/cashprediction/fx/ui");
        Pattern constructor = Pattern.compile("new\\s+(\\w+)(?:<[^>]*>)?\\s*\\(");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int i = 0; i < lines.size(); i++) {
                    var match = constructor.matcher(lines.get(i));
                    while (match.find()) if (required.contains(match.group(1))) {
                        String nearby = String.join("\n", lines.subList(Math.max(0, i - 3), i + 1));
                        assertTrue(nearby.contains("JavaFX: " + match.group(1) + " ") && nearby.contains("Swing:") && nearby.contains("Web:"), path + ":" + (i + 1));
                    }
                }
            }
        }
    }
}
