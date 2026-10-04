package ru.cashprediction.parity.portable;

import java.nio.file.Path;
import java.util.Map;

/** Только guard fixtures: не создают HTTP transport, server, browser или native client. */
public final class NativeWebGoalRestorationBridgeFixture {
    private NativeWebGoalRestorationBridgeFixture() { }
    private static int checks;
    /** Запускает bounded pure checks нового bridge, не повторяя domain seed tests. */
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("FIXTURE_ARGS");
        NativeWebGoalRestorationBridge.requireAddress("http://127.0.0.1:12345/?t=abc_731");checks++;
        for(String bad:new String[]{"https://127.0.0.1:12345/?t=a","http://example.com:12345/?t=a",
            "http://localhost:12345/?t=a","http://127.0.0.1:65536/?t=a","http://127.0.0.1:0/?t=a",
            "http://127.0.0.1:1/?t=","http://127.0.0.1:1/?t=a&extra=b","http://127.0.0.1:1/?t=a#fragment"})
            rejects(()->NativeWebGoalRestorationBridge.requireAddress(bad));
        NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",1,"command","shot restored"),0);checks++;
        rejects(()->NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",1,"command","shot restored"),1));
        rejects(()->NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",2,"command","shot restored"),0));
        rejects(()->NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",1,"command","fill target 450731"),0));
        rejects(()->NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",true,"command","shot restored"),0));
        rejects(()->NativeWebGoalRestorationBridge.requireStep(Map.of("seq",7,"type","test.step","n",1,"command","shot restored","dump","model"),0));
        Path base=Path.of(args[0]).toRealPath();
        NativeWebGoalRestorationBridge.requireOutput(base.resolve("restored-window/actual-output"));checks++;
        rejects(()->NativeWebGoalRestorationBridge.requireOutput(base.resolve("wrong/actual-output")));
        rejects(()->NativeWebGoalRestorationBridge.requireOutput(base));
        System.out.println("WEB_GOAL_PURE_FIXTURES="+checks+";NATIVE_EXECUTED=false;ACTUAL_DOM=PENDING");
    }
    /** Отсутствие ошибки - fixture failure, никогда не молчаливый успех. */
    private static void rejects(Checked action) throws Exception {
        try {action.run();} catch(RuntimeException expected) {checks++;return;}
        throw new AssertionError("EXPECTED_REJECTION");
    }
    /** Операция, допускающая checked path errors. */
    @FunctionalInterface private interface Checked {void run() throws Exception;}
}
