package dummydomain.yetanothercallblocker.data.setupcheck;

import org.junit.Test;

import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Action;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Reason;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Status;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SetupCheckAppUpdateTest {

    private static CheckItem find(SetupCheck.Result result, Type type) {
        for (CheckItem item : result.getItems()) {
            if (item.getType() == type) return item;
        }
        return null;
    }

    @Test
    public void noItemWithoutUpdate() {
        SetupCheck.Result result = SetupCheck.run(new SetupCheckTest.FakeEnvironment());
        assertNull(find(result, Type.APP_UPDATE));
    }

    @Test
    public void updateIsInfoNotProblem() {
        SetupCheckTest.FakeEnvironment env = new SetupCheckTest.FakeEnvironment() {
            @Override
            public String getAvailableAppUpdate() {
                return "0.12.0";
            }
        };
        SetupCheck.Result before = SetupCheck.run(new SetupCheckTest.FakeEnvironment());
        SetupCheck.Result result = SetupCheck.run(env);

        CheckItem item = find(result, Type.APP_UPDATE);
        assertEquals(Status.INFO, item.getStatus());
        assertEquals(Reason.APP_UPDATE_AVAILABLE, item.getReason());
        assertEquals(Action.OPEN_APP_UPDATE, item.getAction());
        assertEquals("0.12.0", item.getDetail());
        assertNull(item.getSource());
        // last item, doesn't change the overall state
        assertEquals(item, result.getItems().get(result.getItems().size() - 1));
        assertEquals(before.getOverallStatus(), result.getOverallStatus());
        assertEquals(before.getProblemCount(), result.getProblemCount());
        assertTrue(result.isProtectionWorking());
    }

}
