# §6.29: harness заранее создаёт аварийный снимок в изолированном CashMemory.
# Время XML-снимка в подготовке harness: 12:00:00.
# Запуск без --selftest-recovery, иначе диалог будет автоматически отвечен.
today 2026-09-13
dump recovery-before-main
answer "Из XML-файла (сохранено 12:00:00)"
dump restored
