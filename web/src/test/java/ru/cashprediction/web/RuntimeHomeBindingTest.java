package ru.cashprediction.web;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Контракт genuine Web entrypoints; class bytes читаются без сервера, контроллера или сетевых запросов. */
class RuntimeHomeBindingTest {
    private static final String ENV = "ru/cashprediction/core/app/AppEnvironment";
    private static final String OPTIONS = "Lru/cashprediction/core/app/LaunchOptions;";
    private static final String E = "L" + ENV + ";";
    private static final String SERVER = "ru/cashprediction/web/WebServer";
    private static final String W = "L" + SERVER + ";";
    private static final String SESSION = "ru/cashprediction/web/WebUpdateSession";
    private static final String S = "L" + SESSION + ";";
    private static final String PREFIX = "(" + E + "Lru/cashprediction/web/ServerLog;IZ";

    @Test void mainDispatchesParsedOptionsToGenuineStartCore() throws Exception {
        var c = code("ru/cashprediction/web/WebMain", "main", "([Ljava/lang/String;)V");
        int parse = call(c, "ru/cashprediction/core/app/LaunchOptions", "parse", "(Ljava/util/List;Ljava/util/Properties;)" + OPTIONS);
        int start = call(c, "ru/cashprediction/web/WebMain", "startCore", "(" + OPTIONS + "[Ljava/lang/String;)V");
        dominates(c, 0, parse, start);
    }

    @Test void environmentAdmissionPrecedesServerWindowAndBrowserBindings() throws Exception {
        var c = code("ru/cashprediction/web/WebMain", "startCore", "(" + OPTIONS + "[Ljava/lang/String;)V");
        int home = call(c, ENV, "from", "(" + OPTIONS + ")" + E);
        int start = call(c, SERVER, "startCore", PREFIX + "[Ljava/lang/String;)" + W);
        int window = call(c, "ru/cashprediction/web/ServerStatusWindow", "show", "(" + W + "Lru/cashprediction/web/ServerLog;)V");
        int browser = call(c, "ru/cashprediction/web/WebMain", "openBrowser", "(" + W + "Lru/cashprediction/web/ServerLog;)V");
        dominates(c, 0, home, start); dominates(c, 0, start, window); dominates(c, 0, start, browser);
    }

    @Test void rawArgumentsOverloadOpensUpdaterBeforeAdmittedServerOverload() throws Exception {
        var c = code(SERVER, "startCore", PREFIX + "[Ljava/lang/String;)" + W);
        int open = call(c, SESSION, "open", "(" + E + "[Ljava/lang/String;)" + S);
        int delegated = call(c, SERVER, "startCore", PREFIX + S + ")" + W);
        dominates(c, 0, open, delegated);
    }

    @Test void refusedUpdaterCannotReachRegisteredHttpOrControllerConstruction() throws Exception {
        var c = code(SERVER, "startCore", PREFIX + S + ")" + W);
        int before = call(c, SESSION, "beforeUi", "()Z");
        int registered = call(c, SERVER, "startRegistered", PREFIX + S + ")" + W);
        dominates(c, 0, before, registered); deniedCannotReach(c, before, registered);
        var registeredCode = code(SERVER, "startRegistered", PREFIX + S + ")" + W);
        int bind = call(registeredCode, "ru/cashprediction/web/PortFinder", "bind", "(Ljava/net/InetAddress;IZLru/cashprediction/web/ServerLog;)Lcom/sun/net/httpserver/HttpServer;");
        int core = call(registeredCode, "ru/cashprediction/web/ui/CoreWebRuntime", "<init>", "(" + E + "Ljava/lang/Runnable;)V");
        dominates(registeredCode, 0, bind, core);
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
