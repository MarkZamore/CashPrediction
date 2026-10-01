# §6.13: конфликт создаётся сохранением и повторным открытием примера.
today 2026-09-13
key Esc
sample
save
dump saved
sample
save
dump overwrite
answer "Отмена"
chooser "Другой план.md"
menu file.saveAs
dump saved-as
sample
save
dump overwrite-again
answer "Перезаписать"
dump overwritten
menu edit.planSettings
fill last cushion="68000"
ok last
signal external-change
save
dump external-conflict
answer "Отмена"
dump conflict-cancelled
save
dump external-conflict-again
answer "Перечитать"
dump reloaded
