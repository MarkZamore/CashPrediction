/** Сборочный инструмент обновлений: только JDK и общее ядро, без лаунчера приложения. */
module ru.cashprediction.updatetool {
    requires ru.cashprediction.core;
    exports ru.cashprediction.updatetool;
}
