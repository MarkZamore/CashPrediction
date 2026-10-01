# §6.28: чистая изолированная папка, без планов и снимков.
today 2026-09-13
dump wizard
key Esc
size 1200 800
dump empty-plan
menu file.new
fill last name=""
dump invalid-name
fill last name="Первый план"
button "Новый план" "Далее ›"
fill last startDate="13.09.2026" startBalance="150000"
dump wizard-balance
button "Новый план" "Далее ›"
dump wizard-operations
button "Новый план" "Готово"
dump created
