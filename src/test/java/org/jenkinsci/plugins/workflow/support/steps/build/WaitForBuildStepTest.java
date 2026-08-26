package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;

import java.util.Collections;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockQueueItemAuthenticator;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class WaitForBuildStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder();

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void waitForBuild() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();

        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatusSuccess(completedUsRun);
        j.assertLogContains("'ds' completed with status " + dsResult, completedUsRun);
    }

    @Test
    void waitForBuildPropagate() throws Exception {
        Result dsResult = Result.FAILURE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        waitForBuild runId: dsRunId, propagate: true""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatus(dsResult, completedUsRun);
        j.assertLogContains("completed with status " + dsResult, completedUsRun);
    }

    @SuppressWarnings("rawtypes")
    @Test
    void waitForBuildAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        j.waitForCompletion(ds1);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1'", true));
        Result dsResult = Result.FAILURE;
        j.assertLogContains("already completed: "+ dsResult, j.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-71342")
    @SuppressWarnings("rawtypes")
    @Test void waitForBuildPropagateAlreadyCompleteFailure() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new FailureBuilder());
        Run ds1 = ds.scheduleBuild2(0).waitForStart();
        assertEquals(1, ds1.getNumber());
        j.waitForCompletion(ds1);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("waitForBuild runId: 'ds#1', propagate: true", true));
        Result dsResult = Result.FAILURE;
        j.assertLogContains("already completed: "+ dsResult, j.buildAndAssertStatus(dsResult, us));
    }

    @Issue("JENKINS-70983")
    @Test
    void waitForUnstableBuildWithWarningAction() throws Exception {
        Result dsResult = Result.UNSTABLE;
        WorkflowJob ds = createWaitingDownStreamJob("wait", dsResult);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        try {
                            waitForBuild runId: dsRunId, propagate: true
                        } finally {
                            echo "'ds' completed with status ${ds.getResult()}"
                        }""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        // signal the downstream run to complete after it has been waited on
        WorkflowRun dsRun = ds.getBuildByNumber(1);
        waitForWaitForBuildAction(dsRun);
        SemaphoreStep.success("wait/1", true);

        // assert upstream build status
        WorkflowRun completedUsRun = j.waitForCompletion(usRun);
        j.assertBuildStatus(dsResult, completedUsRun);
        j.assertLogContains("'ds' completed with status " + dsResult, completedUsRun);

        FlowNode buildTriggerNode = findFirstNodeWithDescriptor(completedUsRun.getExecution(), WaitForBuildStep.DescriptorImpl.class);
        WarningAction action = buildTriggerNode.getAction(WarningAction.class);
        assertNotNull(action);
        assertEquals(Result.UNSTABLE, action.getResult());
    }

    @Issue("JENKINS-71961")
    @Test
    void abortBuild() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the downstream build
        dsRun.getExecutor().interrupt();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsRun));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test
    void interruptFlowPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: true
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsRun));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));
    }

    @Issue("JENKINS-71961")
    @Test
    void interruptFlowNoPropagateAbort() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        def completeDs = waitForBuild runId: dsRunId, propagate: true, propagateAbort: false
                        echo "'ds' completed with status ${completeDs.getResult()}\"""", true));

        // schedule upstream
        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        
        // wait for ds to be scheduled
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        // Abort the upstream build
        usRun.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));

        // Allow the downstream to complete
        SemaphoreStep.success("wait/1", true);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(dsRun));
    }

    private static FlowNode findFirstNodeWithDescriptor(FlowExecution execution, Class<WaitForBuildStep.DescriptorImpl> cls) {
        for (FlowNode node : new FlowGraphWalker(execution)) {
            if (node instanceof StepAtomNode stepAtomNode) {
                if (cls.isInstance(stepAtomNode.getDescriptor())) {
                    return stepAtomNode;
                }
            }
        }
        return null;
    }

    @Issue("SECURITY-3870")
    @Test
    void propagateAbortWithoutCancelPermissionDoesNotAbortDownstream() throws Exception {
        WorkflowJob ds = createWaitingDownStreamJob("wait", Result.SUCCESS);
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
                """
                        def ds = build job: 'ds', waitForStart: true
                        semaphore 'scheduled'
                        def dsRunId = "${ds.getFullProjectName()}#${ds.getNumber()}"
                        waitForBuild runId: dsRunId, propagate: true, propagateAbort: true""", true));

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // us runs as dev, who may build ds but has no job/cancel on it
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(
                Collections.singletonMap("us", User.getById("dev", true).impersonate())));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev")
                .grant(Item.READ, Item.BUILD).onItems(ds).to("dev"));

        WorkflowRun usRun = us.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("scheduled/1", usRun);
        SemaphoreStep.success("scheduled/1", true);

        WorkflowRun dsRun = ds.getBuildByNumber(1);
        SemaphoreStep.waitForStart("wait/1", dsRun);
        waitForWaitForBuildAction(dsRun);

        usRun.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usRun));

        // dev lacks job/cancel on ds, so the downstream must keep running and a permission message is logged
        j.assertLogContains("dev is missing the Job/Cancel permission to abort ds", usRun);
        assertTrue(dsRun.isBuilding(), "downstream ds should still be running");

        SemaphoreStep.success("wait/1", true);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(dsRun));
    }

    private WorkflowJob createWaitingDownStreamJob(String semaphoreName, Result result) throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition(
            "semaphore('" + semaphoreName + "')\n" +
            "catchError(buildResult: '" + result.toString() + "') {\n" +
            "    error('')\n" +
            "}", false));
        return ds;
    }

    private void waitForWaitForBuildAction(WorkflowRun r) {
       await().until(() -> r.getAction(WaitForBuildAction.class) != null);
    }

}
