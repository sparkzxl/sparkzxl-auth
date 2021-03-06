package com.github.sparkzxl.workflow.domain.service.act;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.date.DatePattern;
import com.github.sparkzxl.core.utils.DateUtils;
import com.github.sparkzxl.database.factory.CustomThreadFactory;
import com.github.sparkzxl.workflow.application.service.act.IProcessHistoryService;
import com.github.sparkzxl.workflow.application.service.act.IProcessRepositoryService;
import com.github.sparkzxl.workflow.application.service.act.IProcessRuntimeService;
import com.github.sparkzxl.workflow.application.service.act.IProcessTaskService;
import com.github.sparkzxl.workflow.application.service.ext.IExtHiTaskStatusService;
import com.github.sparkzxl.workflow.application.service.ext.IExtProcessStatusService;
import com.github.sparkzxl.workflow.domain.repository.IExtProcessUserRepository;
import com.github.sparkzxl.workflow.dto.ProcessHistory;
import com.github.sparkzxl.workflow.dto.ProcessHistoryParam;
import com.github.sparkzxl.workflow.infrastructure.constant.WorkflowConstants;
import com.github.sparkzxl.workflow.infrastructure.diagram.CustomProcessDiagramGeneratorImpl;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtHiTaskStatus;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtProcessStatus;
import com.github.sparkzxl.workflow.infrastructure.enums.TaskStatusEnum;
import com.github.sparkzxl.workflow.infrastructure.utils.CloseableUtils;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.bpmn.model.FlowNode;
import org.activiti.bpmn.model.SequenceFlow;
import org.activiti.engine.HistoryService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.task.Comment;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * description: ???????????? ???????????????
 *
 * @author charles.zhou
 * @date 2020-07-17 15:21:22
 */
@Service
@Slf4j
public class ProcessHistoryServiceImpl implements IProcessHistoryService {

    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2,
            4,
            10,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(30),
            new CustomThreadFactory());

    @Autowired
    private HistoryService historyService;

    @Autowired
    private IProcessRepositoryService processRepositoryService;

    @Autowired
    private IProcessTaskService processTaskService;

    @Autowired
    private IProcessRuntimeService processRuntimeService;

    @Autowired
    private IExtHiTaskStatusService actHiTaskStatusService;

    @Autowired
    private CustomProcessDiagramGeneratorImpl processDiagramGenerator;

    @Autowired
    private IExtProcessStatusService processTaskStatusService;

    @Autowired
    private IExtProcessUserRepository processUserRepository;

    @Override
    public HistoricProcessInstance getHistoricProcessInstance(String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
    }

    @Override
    public List<HistoricTaskInstance> getHistoricTasksByAssigneeId(String assignee) {
        return historyService.createHistoricTaskInstanceQuery().taskAssignee(assignee).list();
    }

    @Override
    public List<HistoricTaskInstance> getHistoricTasksByProcessInstanceId(String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).orderByTaskId().asc().list();
    }

    @Override
    public HistoricTaskInstance getHistoricTasksByTaskId(String taskId) {
        return historyService.createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
    }

    private List<ProcessHistory> getProcessHistoryByBusinessId(String businessId) {
        List<ExtProcessStatus> processStatusList = processTaskStatusService.getExtProcessStatusList(businessId);
        List<ProcessHistory> processHistories = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(processStatusList)) {
            List<String> processInstanceIds = processStatusList.stream().map(ExtProcessStatus::getProcessInstanceId)
                    .collect(Collectors.toList());
            processInstanceIds.forEach(processInstanceId -> {
                try {
                    List<ProcessHistory> processHistoryList = getProcessHistories(processInstanceId);
                    processHistories.addAll(processHistoryList);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            });
        }
        return buildHistoryUserName(processHistories);
    }

    private List<ProcessHistory> getProcessHistories(String processInstanceId) throws InterruptedException, ExecutionException {
        CompletableFuture<List<ProcessHistory>> hiActInsCompletableFuture =
                CompletableFuture.supplyAsync(() -> buildActivityProcessHistory(processInstanceId), threadPoolExecutor);

        CompletableFuture<List<ProcessHistory>> hiTaskInsCompletableFuture =
                CompletableFuture.supplyAsync(() -> buildTaskProcessHistory(processInstanceId), threadPoolExecutor);

        CompletableFuture<List<ProcessHistory>> processHistoryCompletableFuture = hiActInsCompletableFuture
                .thenCombine(hiTaskInsCompletableFuture, org.apache.commons.collections4.ListUtils::union);
        List<ProcessHistory> processHistories = processHistoryCompletableFuture.get();
        processHistories.sort(Comparator.comparing(ProcessHistory::getStartTime));
        return processHistories;
    }

    private List<ProcessHistory> getProcessHistoryByProcessInstanceId(String processInstanceId) throws ExecutionException, InterruptedException {
        List<ProcessHistory> processHistories = getProcessHistories(processInstanceId);
        return buildHistoryUserName(processHistories);
    }

    private List<ProcessHistory> buildHistoryUserName(List<ProcessHistory> processHistories) {
        List<String> userIdList = processHistories.stream().map(ProcessHistory::getAssignee).collect(Collectors.toList());
        Map<String, String> userMap = processUserRepository.findUserByIds(userIdList);
        processHistories.forEach(processHistory -> processHistory.setAssigneeName(userMap.get(processHistory.getAssignee())));
        return processHistories;
    }

    private List<ProcessHistory> buildTaskProcessHistory(String processInstanceId) {
        List<ProcessHistory> processHistories = Lists.newArrayList();
        try {
            // ??????????????????????????????
            CompletableFuture<List<ExtHiTaskStatus>> hiTaskStatusCompletableFuture =
                    CompletableFuture.supplyAsync(() -> actHiTaskStatusService.getProcessHistory(processInstanceId), threadPoolExecutor);
            // ????????????????????????
            CompletableFuture<List<HistoricTaskInstance>> hiTakInsCompletableFuture =
                    CompletableFuture.supplyAsync(() -> getHistoricTasksByProcessInstanceId(processInstanceId), threadPoolExecutor);
            // ????????????????????????
            CompletableFuture<List<Comment>> hiCommentCompletableFuture = CompletableFuture.supplyAsync(() -> processTaskService
                    .getProcessInstanceComments(processInstanceId, "comment"), threadPoolExecutor);
            List<ExtHiTaskStatus> actHiTaskStatusList = hiTaskStatusCompletableFuture.get();
            Map<String, Integer> actHiTaskStatusMap = actHiTaskStatusList.stream().collect(Collectors.toMap(ExtHiTaskStatus::getTaskId, ExtHiTaskStatus::getTaskStatus));
            List<HistoricTaskInstance> historicTaskInstances = hiTakInsCompletableFuture.get();
            List<Comment> commentList = hiCommentCompletableFuture.get();
            Map<String, String> commentMap = commentList.stream().collect(Collectors.toMap(Comment::getTaskId, Comment::getFullMessage));
            // ????????????????????????
            historicTaskInstances.forEach(historicTaskInstance -> {
                String assignee = historicTaskInstance.getAssignee();
                ProcessHistory processHistory = ProcessHistory.builder()
                        .processInstanceId(processInstanceId)
                        .taskName(historicTaskInstance.getName())
                        .startTime(historicTaskInstance.getStartTime())
                        .endTime(historicTaskInstance.getEndTime())
                        .duration(historicTaskInstance.getDurationInMillis())
                        .assignee(assignee)
                        .dueDate(historicTaskInstance.getDueDate())
                        .build();
                if (ObjectUtils.isNotEmpty(historicTaskInstance.getDurationInMillis())) {
                    String durationTime = DateUtils.formatBetween(historicTaskInstance.getDurationInMillis());
                    processHistory.setDurationTime(durationTime);
                    Optional<ExtHiTaskStatus> actHiTaskStatusOptional =
                            actHiTaskStatusList.stream().filter(item -> StringUtils.equals(historicTaskInstance.getId(),
                                    item.getTaskId())).findFirst();
                    actHiTaskStatusOptional.ifPresent(value -> processHistory.setTaskStatus(value.getTaskStatus()));
                    TaskStatusEnum taskStatusEnum = TaskStatusEnum.get(actHiTaskStatusMap.get(historicTaskInstance.getId()));
                    if (taskStatusEnum != null) {
                        processHistory.setTaskStatus(taskStatusEnum.getCode());
                        processHistory.setTaskStatusName(taskStatusEnum.getDesc());
                    }
                    processHistory.setComment(commentMap.get(historicTaskInstance.getId()));
                } else {
                    processHistory.setTaskStatus(TaskStatusEnum.IN_HAND.getCode());
                    processHistory.setTaskStatusName(TaskStatusEnum.IN_HAND.getDesc());
                }
                processHistories.add(processHistory);
            });
        } catch (Exception e) {
            log.error("?????????????????????????????? Exception {}", e.getMessage());
        }
        return processHistories;
    }

    private List<ProcessHistory> buildActivityProcessHistory(String processInstanceId) {
        HistoricProcessInstance historicProcessInstance = getHistoricProcessInstance(processInstanceId);
        List<ProcessHistory> processHistories = Lists.newArrayList();
        List<HistoricActivityInstance> historicActivityInstances = getHistoricActivityInstance(processInstanceId);
        List<HistoricActivityInstance> specialHistoricActivityInstances =
                historicActivityInstances.stream().filter(item -> WorkflowConstants.ActType.START_EVENT.equals(item.getActivityType())
                        || WorkflowConstants.ActType.END_EVENT.equals(item.getActivityType()))
                        .collect(Collectors.toList());
        specialHistoricActivityInstances.forEach(historicActivityInstance -> {
            ProcessHistory processHistory = ProcessHistory.builder()
                    .processInstanceId(processInstanceId)
                    .startTime(historicActivityInstance.getStartTime())
                    .endTime(historicActivityInstance.getEndTime())
                    .duration(historicActivityInstance.getDurationInMillis())
                    .build();

            if (WorkflowConstants.ActType.START_EVENT.equals(historicActivityInstance.getActivityType())) {
                processHistory.setAssignee(historicProcessInstance.getStartUserId());
                processHistory.setTaskStatus(TaskStatusEnum.START.getCode());
                processHistory.setTaskStatusName(TaskStatusEnum.START.getDesc());
                processHistory.setTaskName("????????????");
                processHistory.setDurationTime(DateUtils.formatBetween(historicActivityInstance.getStartTime(), historicActivityInstance.getEndTime()));
            }
            if (WorkflowConstants.ActType.END_EVENT.equals(historicActivityInstance.getActivityType())) {
                if (historicActivityInstances.size() > 1 && ObjectUtils.isNotEmpty(historicActivityInstances.get(historicActivityInstances.size() - 1))) {
                    processHistory.setAssignee(historicActivityInstances.get(historicActivityInstances.size() - 1).getAssignee());
                }
                processHistory.setTaskStatus(TaskStatusEnum.END.getCode());
                processHistory.setTaskStatusName(TaskStatusEnum.END.getDesc());
                processHistory.setTaskName("????????????");
                processHistory.setDurationTime(DateUtils.formatBetween(historicActivityInstance.getStartTime(), historicActivityInstance.getEndTime()));
            }
            processHistories.add(processHistory);
        });
        return processHistories;
    }

    public List<HistoricActivityInstance> getHistoricActivityInstance(String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceId().asc().list();
    }

    @Override
    public String getProcessImage(String processInstanceId) {
        InputStream imageStream = null;
        String imageBase64;
        try {
            if (StringUtils.isBlank(processInstanceId)) {
                log.error("????????????");
            }
            // ????????????????????????
            HistoricProcessInstance processInstance = getHistoricProcessInstance(processInstanceId);

            // ??????????????????ID
            String processDefinitionId = processInstance.getProcessDefinitionId();

            // ????????????????????????
            BpmnModel bpmnModel = processRepositoryService.getBpmnModel(processDefinitionId);

            // ????????????????????????????????????
            List<HistoricActivityInstance> historicActivityInstance = getHistoricActivityInstance(processInstanceId);

            // ????????????id??????
            List<String> highLightedActivitis = new ArrayList<>();
            for (HistoricActivityInstance tempActivity : historicActivityInstance) {
                String activityId = tempActivity.getActivityId();
                highLightedActivitis.add(activityId);
            }

            // ????????????id??????
            List<String> highLightedFlows = getHighLightedFlows(bpmnModel, historicActivityInstance);

            Set<String> currIds = processRuntimeService.getExecutionByProcInsId(processInstanceId)
                    .stream().map(Execution::getActivityId).collect(Collectors.toSet());

            imageStream = processDiagramGenerator.generateDiagram(bpmnModel, "png", highLightedActivitis,
                    highLightedFlows, "??????", "??????", "??????",
                    null, 1.0,
                    new Color[]{WorkflowConstants.COLOR_NORMAL, WorkflowConstants.COLOR_CURRENT}, currIds);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n = 0;
            while (-1 != (n = imageStream.read(buffer))) {
                output.write(buffer, 0, n);
            }
            byte[] processImageByte = output.toByteArray();
            imageBase64 = "data:image/jpeg;base64,".concat(Base64.encode(processImageByte));
        } catch (IOException e) {
            throw new RuntimeException("?????????????????????", e);
        } finally {
            CloseableUtils.close(imageStream);
        }
        return imageBase64;
    }

    private List<String> getHighLightedFlows(BpmnModel bpmnModel, List<HistoricActivityInstance> historicActivityInstances) {
        // 24?????????
        SimpleDateFormat df = new SimpleDateFormat(DatePattern.NORM_DATETIME_PATTERN);
        // ????????????????????????flowId
        List<String> highFlows = Lists.newArrayList();

        for (int i = 0; i < historicActivityInstances.size() - 1; i++) {
            // ?????????????????????????????????
            // ?????????????????????????????????
            FlowNode activityImpl = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstances.get(i).getActivityId());
            // ?????????????????????????????????????????????
            List<FlowNode> sameStartTimeNodes = Lists.newArrayList();
            FlowNode sameActivityImpl1 = null;
            // ???????????????
            HistoricActivityInstance activityInstance = historicActivityInstances.get(i);
            HistoricActivityInstance activityInstance1;

            for (int k = i + 1; k <= historicActivityInstances.size() - 1; k++) {
                // ?????????1?????????
                activityInstance1 = historicActivityInstances.get(k);

                // ??????usertask???????????????????????????????????????????????????????????????????????????????????????
                if ("userTask".equals(activityInstance.getActivityType()) && "userTask".equals(activityInstance1.getActivityType()) &&
                        df.format(activityInstance.getStartTime()).equals(df.format(activityInstance1.getStartTime()))) {
                } else {
                    // ????????????????????????????????????
                    sameActivityImpl1 = (FlowNode) bpmnModel.getMainProcess().getFlowElement(historicActivityInstances.get(k).getActivityId());
                    break;
                }
            }
            // ????????????????????????????????????????????????????????????
            sameStartTimeNodes.add(sameActivityImpl1);
            for (int j = i + 1; j < historicActivityInstances.size() - 1; j++) {
                // ?????????????????????
                HistoricActivityInstance activityImpl1 = historicActivityInstances.get(j);
                // ?????????????????????
                HistoricActivityInstance activityImpl2 = historicActivityInstances.get(j + 1);

                // ???????????????????????????????????????????????????????????????
                if (df.format(activityImpl1.getStartTime()).equals(df.format(activityImpl2.getStartTime()))) {
                    FlowNode sameActivityImpl2 = (FlowNode) bpmnModel.getMainProcess().getFlowElement(activityImpl2.getActivityId());
                    sameStartTimeNodes.add(sameActivityImpl2);
                } else {
                    // ????????????????????????
                    break;
                }
            }
            // ?????????????????????????????????
            List<SequenceFlow> pvmTransitions = activityImpl.getOutgoingFlows();

            // ???????????????????????????
            for (SequenceFlow pvmTransition : pvmTransitions) {
                // ?????????????????????????????????????????????????????????????????????????????????id?????????????????????
                FlowNode pvmActivityImpl = (FlowNode) bpmnModel.getMainProcess().getFlowElement(pvmTransition.getTargetRef());
                if (sameStartTimeNodes.contains(pvmActivityImpl)) {
                    highFlows.add(pvmTransition.getId());
                }
            }
        }
        return highFlows;
    }

    @Override
    public List<ProcessHistory> processHistoryList(ProcessHistoryParam processHistoryParam) {
        if (processHistoryParam.getType().equals(1)) {
            return getProcessHistoryByBusinessId(processHistoryParam.getBusinessId());
        } else {
            try {
                return getProcessHistoryByProcessInstanceId(processHistoryParam.getProcessInstanceId());
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                log.error("?????????????????????????????????{}", e.getMessage());
                return null;
            }
        }
    }
}
