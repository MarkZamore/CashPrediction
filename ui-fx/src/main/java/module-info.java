/**
 * Настольный клиент CashPrediction на JavaFX.
 *
 * <p>Пакет с классом приложения экспортируется для javafx.graphics, иначе {@code Application.launch}
 * не сможет создать экземпляр {@code FxMain}. {@code java.logging} нужен, чтобы в {@code main()} приглушить
 * журнал {@code java.util.prefs}: иначе при недоступном реестре JDK печатает предупреждения в консоль.</p>
 */
module ru.cashprediction.fx {
    requires ru.cashprediction.core;
    requires javafx.controls;
    requires java.logging;

    exports ru.cashprediction.fx to javafx.graphics;
}
