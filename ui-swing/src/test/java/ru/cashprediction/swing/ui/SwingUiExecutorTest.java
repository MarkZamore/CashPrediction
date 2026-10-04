package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Проверяет сохранённый контракт перенесённого исполнителя без создания окон Swing. */
class SwingUiExecutorTest {
    /** Внешний поток возвращается сразу, а задача выполняется после освобождения EDT. */
    @Test void backgroundSubmissionUsesEdtQueue() throws Exception {
        var executor = new SwingUiExecutor();
        assertFalse(executor.isUiThread());
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executed = new CountDownLatch(1);
        var onEdt = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            occupied.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { onEdt.set(executor.isUiThread()); executed.countDown(); });
            assertEquals(1L, executed.getCount(), "The task must remain queued behind the occupied EDT");
        } finally { release.countDown(); }
        assertTrue(executed.await(5, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
    }

    /** Внутри EDT задача выполняется синхронно и исключение передаётся вызывающему коду. */
    @Test void edtSubmissionRunsInlineAndPropagatesFailure() throws Exception {
        var executor = new SwingUiExecutor();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(executor.isUiThread());
            List<String> order = new ArrayList<>();
            order.add("before"); executor.execute(() -> order.add("task")); order.add("after");
            assertEquals(List.of("before", "task", "after"), order);
            var expected = new IllegalStateException("sentinel");
            assertSame(expected, assertThrows(IllegalStateException.class, () -> executor.execute(() -> { throw expected; })));
        });
    }
}
