today 2026-09-13
key Esc
sample
# Временное сообщение открытия примера не должно зависеть от скорости нативных меню.
wait 10001
context row:r1@2026-10-05
dump rule-context
context row:start
dump start-context
context total:total@2026-09
dump total-context
context pastHeader
dump past-context
context card:now
dump card-context
view CHART
context chart:300,200
dump chart-context
context chart:0,0
dump chart-outside
