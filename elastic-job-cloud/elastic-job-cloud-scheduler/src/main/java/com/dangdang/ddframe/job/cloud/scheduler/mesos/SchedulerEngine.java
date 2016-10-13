/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.mesos;

import com.dangdang.ddframe.job.cloud.scheduler.context.TaskContext;
import com.netflix.fenzo.TaskScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import java.util.List;

/**
 * 作业云引擎.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
@Slf4j
public final class SchedulerEngine implements Scheduler {
    
    private final LeasesQueue leasesQueue;
    
    private final TaskScheduler taskScheduler;
    
    private final FacadeService facadeService;
    
    @Override
    public void registered(final SchedulerDriver schedulerDriver, final Protos.FrameworkID frameworkID, final Protos.MasterInfo masterInfo) {
        log.info("call registered");
        facadeService.start();
        taskScheduler.expireAllLeases();
    }
    
    @Override
    public void reregistered(final SchedulerDriver schedulerDriver, final Protos.MasterInfo masterInfo) {
        log.info("call reregistered");
        facadeService.start();
        taskScheduler.expireAllLeases();
    }
    
    @Override
    public void resourceOffers(final SchedulerDriver schedulerDriver, final List<Protos.Offer> offers) {
        for (Protos.Offer offer: offers) {
            log.trace("Adding offer {} from host {}", offer.getId(), offer.getHostname());
            leasesQueue.offer(offer);
        }
    }
    
    @Override
    public void offerRescinded(final SchedulerDriver schedulerDriver, final Protos.OfferID offerID) {
        log.trace("call offerRescinded: {}", offerID);
        taskScheduler.expireLease(offerID.getValue());
    }
    
    @Override
    public void statusUpdate(final SchedulerDriver schedulerDriver, final Protos.TaskStatus taskStatus) {
        String taskId = taskStatus.getTaskId().getValue();
        TaskContext taskContext = TaskContext.from(taskId);
        log.trace("call statusUpdate task state is: {}, task id is: {}", taskStatus.getState(), taskId);
        switch (taskStatus.getState()) {
            case TASK_RUNNING:
                if ("BEGIN".equals(taskStatus.getMessage())) {
                    facadeService.updateDaemonStatus(taskContext, false);
                } else if ("COMPLETE".equals(taskStatus.getMessage())) {
                    facadeService.updateDaemonStatus(taskContext, true);
                }
                break;
            case TASK_FINISHED:
                facadeService.removeRunning(taskContext);
                unAssignTask(taskId);
                break;
            case TASK_KILLED:
                facadeService.removeRunning(taskContext);
                facadeService.addDaemonJobToReadyQueue(taskContext.getMetaInfo().getJobName());
                unAssignTask(taskId);
                break;
            case TASK_LOST:
            case TASK_FAILED:
            case TASK_ERROR:
                log.warn("task id is: {}, status is: {}, message is: {}, source is: {}", taskId, taskStatus.getState(), taskStatus.getMessage(), taskStatus.getSource());
                facadeService.removeRunning(taskContext);
                facadeService.recordFailoverTask(taskContext);
                facadeService.addDaemonJobToReadyQueue(taskContext.getMetaInfo().getJobName());
                unAssignTask(taskId);
                break;
            default:
                break;
        }
    }
    
    private void unAssignTask(final String taskId) {
        String hostname = facadeService.popMapping(taskId);
        if (null != hostname) {
            taskScheduler.getTaskUnAssigner().call(taskId, hostname);
        }
    }
    
    @Override
    public void frameworkMessage(final SchedulerDriver schedulerDriver, final Protos.ExecutorID executorID, final Protos.SlaveID slaveID, final byte[] bytes) {
        log.trace("call frameworkMessage slaveID: {}, bytes: {}", slaveID, new String(bytes));
    }
    
    @Override
    public void disconnected(final SchedulerDriver schedulerDriver) {
        log.warn("call disconnected");
        facadeService.stop();
    }
    
    @Override
    public void slaveLost(final SchedulerDriver schedulerDriver, final Protos.SlaveID slaveID) {
        log.warn("call slaveLost slaveID is: {}", slaveID);
        taskScheduler.expireAllLeasesByVMId(slaveID.getValue());
    }
    
    @Override
    public void executorLost(final SchedulerDriver schedulerDriver, final Protos.ExecutorID executorID, final Protos.SlaveID slaveID, final int i) {
        log.debug("call executorLost slaveID is: {}, executorID is: {}", slaveID, executorID);
    }
    
    @Override
    public void error(final SchedulerDriver schedulerDriver, final String message) {
        log.error("call error, message is: {}", message);
    }
}
