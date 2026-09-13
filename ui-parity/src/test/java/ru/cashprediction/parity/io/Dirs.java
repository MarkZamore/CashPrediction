package ru.cashprediction.parity.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Работа с рабочими папками стенда (домашние папки клиентов, профили браузера) внутри {@code target}.
 */
public final class Dirs {

    private Dirs() {
    }

    /**
     * Удаляет папку со всем содержимым; отсутствие папки не ошибка.
     *
     * @param dir папка
     * @throws IOException если какой-то файл не удалился (например, ещё занят процессом)
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            // Сначала самые глубокие пути: папку можно удалить только пустой.
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : paths) {
            Files.deleteIfExists(p);
        }
    }

    /**
     * Имена непосредственных элементов папки по алфавиту.
     *
     * @param dir папка
     * @return имена файлов и папок
     * @throws IOException если папку не прочитать
     */
    public static List<String> names(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
