today 2026-09-13
key Esc
sample
menu file.rename
fill last value=""
dump rename-invalid
fill last value="Тестовый план"
dump rename-valid
cancel last
menu tools.currency
dump currency
fill last value="custom"
ok last
dump custom-currency
fill last value="CNY"
ok last
dump currency-applied
menu edit.reconcile
fill last value="bad"
dump reconcile-invalid
fill last value="-1000"
dump reconcile-negative
cancel last
menu view.horizonMonths
fill last value="0"
dump horizon-invalid
fill last value="18"
ok last
menu file.exportCsv
dump csv
cancel last
chooser cancel
menu file.openFile
answer "Не сохранять"
dump open-file-cancelled
menu edit.planSettings
fill last startDate="01.11.2026"
ok last
menu edit.actualize
dump actualize-unavailable
answer "ОК"
menu edit.reconcile
dump reconcile-unavailable
answer "ОК"
