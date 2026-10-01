today 2026-09-13
key Esc
sample
menu edit.addIncome
dump income-create
fill last title="Новый доход" amount="0"
dump income-invalid
fill last amount="25000"
dump income-valid
ok last
dump income-added
menu edit.addExpense
fill last title="Новый расход" amount="1500"
dump expense-create
cancel last
menu edit.addOneTime
fill last title="Покупка" amount="bad"
dump onetime-invalid
fill last amount="1200" date="15.10.2026"
dump onetime-valid
ok last
select r1@2026-10-05
menu edit.adjust
dump adjustment
fill last amount="0"
dump adjustment-invalid
fill last amount="81000"
ok last
dump adjusted
select r1@2026-10-05
menu edit.edit
dump rule-edit
context preview:last:1
dump preview-context
context preview:last:1
menu ctx.preview.adjust
dump nested-adjustment
fill last amount="82000"
ok last
dump parent-editor
cancel last
