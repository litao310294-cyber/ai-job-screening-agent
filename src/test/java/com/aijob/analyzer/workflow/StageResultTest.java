package com.aijob.analyzer.workflow;

import com.aijob.analyzer.workflow.domain.ErrorCategory;
import com.aijob.analyzer.workflow.domain.StageSignal;
import com.aijob.analyzer.workflow.domain.WorkflowError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StageResultTest {

    @Test
    void successResultMustNotHaveError() {
        var res = StageResult.success();
        assertEquals(StageSignal.SUCCESS, res.signal());
        assertNull(res.error());
    }

    @Test
    void failedResultMustHaveError() {
        var error = new WorkflowError("ERR001", ErrorCategory.PROVIDER, true, "error");
        var res = StageResult.failed(error);
        assertEquals(StageSignal.FAILED, res.signal());
        assertEquals(error, res.error());
    }

    @Test
    void rejectInvalidCombinations() {
        var error = new WorkflowError("ERR001", ErrorCategory.PROVIDER, true, "error");

        // FAILED without error
        assertThrows(IllegalArgumentException.class, () -> new StageResult(StageSignal.FAILED, null));

        // SUCCESS with error
        assertThrows(IllegalArgumentException.class, () -> new StageResult(StageSignal.SUCCESS, error));
    }
}
