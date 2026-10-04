package ru.cashprediction.fx;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Контракт реальных entrypoint bindings без запуска toolkit или подмены бизнес-логики.
 * Проверяет compiled CFG, а не GUI readiness; cached environment в start является отдельным допущенным путём.
 */
class RuntimeHomeBindingTest {
    private static final String ENV = "ru/cashprediction/core/app/AppEnvironment";
    private static final String OPTIONS = "Lru/cashprediction/core/app/LaunchOptions;";
    private static final String E = "L" + ENV + ";";
    private static final String SESSION = "ru/cashprediction/fx/ui/FxUpdateSession";
    private static final String S = "L" + SESSION + ";";
    private static final String APP = "ru/cashprediction/fx/ui/FxApp";

    @Test void environmentConstructorCannotReturnWithoutCanonicalGuard() throws Exception {
        var c = code(ENV, "<init>", "(" + OPTIONS + "Ljava/nio/file/Path;Ljava/nio/file/Path;Lru/cashprediction/core/app/AppClock;)V");
        int guard = call(c, "ru/cashprediction/core/io/CanonicalPaths", "requireCashMemory", "(Ljava/nio/file/Path;Ljava/nio/file/Path;)V");
        int returns = 0;
        for (int i = 0; i < c.size(); i++) if (c.get(i) instanceof Instruction v && v.opcode() == Opcode.RETURN) {
            dominates(c, 0, guard, i); returns++;
        }
        assertEquals(1, returns);
    }

    @Test void mainBuildsGuardedEnvironmentBeforeUpdaterAndLaunch() throws Exception {
        var c = code("ru/cashprediction/fx/FxMain", "main", "([Ljava/lang/String;)V");
        int home = call(c, ENV, "from", "(" + OPTIONS + ")" + E);
        int open = call(c, SESSION, "open", "(" + E + "[Ljava/lang/String;)" + S);
        int before = call(c, SESSION, "beforeUi", "()Z");
        int launch = call(c, "ru/cashprediction/fx/FxMain", "launch", "(Ljava/lang/Class;[Ljava/lang/String;)V");
        dominates(c, 0, home, open); dominates(c, 0, open, before); dominates(c, 0, before, launch);
        deniedCannotReach(c, before, launch);
    }

    @Test void directStartNewEnvironmentPrecedesPlatformConfigurationAndApp() throws Exception {
        var c = code("ru/cashprediction/fx/FxMain", "start", "(Ljavafx/stage/Stage;)V");
        int home = call(c, ENV, "from", "(" + OPTIONS + ")" + E);
        int open = call(c, SESSION, "open", "(" + E + "[Ljava/lang/String;)" + S);
        int before = call(c, SESSION, "beforeUi", "()Z");
        int platform = call(c, "javafx/application/Platform", "setImplicitExit", "(Z)V");
        int app = call(c, APP, "start", "(Ljavafx/stage/Stage;" + E + S + ")V");
        // В cached ветви окружение уже принято main; в новой ветви допуск обязателен.
        assertTrue(home < open && open < before && before < platform && platform < app);
        dominates(c, home, home, open); dominates(c, home, open, before);
        dominates(c, home, before, platform); dominates(c, 0, platform, app);
        deniedCannotReach(c, before, platform);
    }

    @Test void optionsAppOverloadCannotReachRendererWithoutHomeAndAdmission() throws Exception {
        var c = code(APP, "start", "(Ljavafx/stage/Stage;" + OPTIONS + ")V");
        int home = call(c, ENV, "from", "(" + OPTIONS + ")" + E);
        int open = call(c, SESSION, "open", "(" + E + "[Ljava/lang/String;)" + S);
        int before = call(c, SESSION, "beforeUi", "()Z");
        int app = call(c, APP, "start", "(Ljavafx/stage/Stage;" + E + S + ")V");
        dominates(c, 0, home, open); dominates(c, 0, open, before); dominates(c, 0, before, app);
        deniedCannotReach(c, before, app);
        var renderer = code(APP, "start", "(Ljavafx/stage/Stage;" + E + S + ")V");
        int port = call(renderer, "ru/cashprediction/fx/ui/FxUiPort", "<init>", "(Ljavafx/stage/Stage;Ljava/util/function/Supplier;)V");
        int controller = call(renderer, "ru/cashprediction/core/app/AppController", "<init>", "(Lru/cashprediction/core/app/UiPort;" + E + ")V");
        dominates(renderer, 0, port, controller);
    }

    /** Читает реальные class bytes, не загружая и не инициализируя проверяемый класс. */
    private static List<CodeElement> code(String owner, String name, String descriptor) throws Exception {
        try (var input = RuntimeHomeBindingTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input, owner);
            var methods = ClassFile.of().parse(input.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().stringValue().equals(name)
                            && m.methodType().stringValue().equals(descriptor)).toList();
            assertEquals(1, methods.size(), owner + "." + name + descriptor);
            return methods.getFirst().code().orElseThrow().elementList();
        }
    }

    /** Требует единственный вызов именно указанного владельца, имени и JVM descriptor. */
    private static int call(List<CodeElement> code, String owner, String name, String descriptor) {
        var matches = new ArrayList<Integer>();
        for (int i = 0; i < code.size(); i++)
            if (code.get(i) instanceof InvokeInstruction v && v.owner().asInternalName().equals(owner)
                    && v.name().stringValue().equals(name) && v.type().stringValue().equals(descriptor)) matches.add(i);
        assertEquals(1, matches.size(), owner + "." + name + descriptor);
        return matches.getFirst();
    }

    /** Проверяет нормальные CFG-рёбра; исключения не считаются успешным допуском. Switch запрещён fail-closed. */
    private static boolean reaches(List<CodeElement> code, int start, int target, int blocked) {
        var labels = new IdentityHashMap<Label, Integer>();
        for (int i = 0; i < code.size(); i++) if (code.get(i) instanceof LabelTarget l) labels.put(l.label(), i);
        var todo = new ArrayDeque<Integer>(); var seen = new HashSet<Integer>(); todo.add(start);
        while (!todo.isEmpty()) {
            int i = todo.removeFirst();
            if (i < 0 || i >= code.size() || i == blocked || !seen.add(i)) continue;
            if (i == target) return true;
            var e = code.get(i);
            if (e instanceof Instruction instruction) {
                String op = instruction.opcode().name();
                assertFalse(op.contains("SWITCH") || op.equals("JSR") || op.equals("RET"), "Unsupported CFG");
                if (op.endsWith("RETURN") || op.equals("ATHROW")) continue;
                if (e instanceof BranchInstruction b) {
                    todo.add(Objects.requireNonNull(labels.get(b.target())));
                    if (op.equals("GOTO") || op.equals("GOTO_W")) continue;
                }
            }
            todo.add(i + 1);
        }
        return false;
    }

    /** Барьер должен встречаться на каждом нормальном пути к защищённому вызову. */
    private static void dominates(List<CodeElement> c, int start, int barrier, int target) {
        assertTrue(reaches(c, start, target, -1), "Unreachable protected binding");
        assertFalse(reaches(c, start, target, barrier), "Guard bypass");
    }

    /** Отрицательное beforeUi не может дойти до защищённого вызова. */
    private static void deniedCannotReach(List<CodeElement> c, int admission, int protectedCall) {
        int next = admission + 1;
        while (!(c.get(next) instanceof Instruction)) next++;
        assertTrue(c.get(next) instanceof BranchInstruction, "Missing admission branch");
        var branch = (BranchInstruction)c.get(next);
        int target = -1;
        for (int i = 0; i < c.size(); i++) if (c.get(i) instanceof LabelTarget l && l.label() == branch.target()) target = i;
        assertTrue(target >= 0);
        int denied = switch (branch.opcode().name()) {
            case "IFEQ" -> target;
            case "IFNE" -> next + 1;
            default -> throw new AssertionError("Nonboolean admission branch");
        };
        assertFalse(reaches(c, denied, protectedCall, -1), "Denied admission reaches side effect");
    }

}
