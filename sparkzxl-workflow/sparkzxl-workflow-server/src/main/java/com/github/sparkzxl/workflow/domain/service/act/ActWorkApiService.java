package com.github.sparkzxl.workflow.domain.service.act;

import cn.hutool.core.exceptions.ExceptionUtil;
import com.github.sparkzxl.core.base.result.ApiResponseStatus;
import com.github.sparkzxl.core.support.BizExceptionAssert;
import com.github.sparkzxl.workflow.application.service.act.IProcessRepositoryService;
import com.github.sparkzxl.workflow.application.service.act.IProcessRuntimeService;
import com.github.sparkzxl.workflow.application.service.act.IProcessTaskService;
import com.github.sparkzxl.workflow.application.service.ext.IExtHiTaskStatusService;
import com.github.sparkzxl.workflow.application.service.ext.IExtProcessStatusService;
import com.github.sparkzxl.workflow.application.service.ext.IExtProcessTaskRuleService;
import com.github.sparkzxl.workflow.domain.model.DriveProcess;
import com.github.sparkzxl.workflow.domain.model.DriverData;
import com.github.sparkzxl.workflow.domain.repository.IExtProcessUserRepository;
import com.github.sparkzxl.workflow.dto.DriverResult;
import com.github.sparkzxl.workflow.infrastructure.act.DeleteTaskCmd;
import com.github.sparkzxl.workflow.infrastructure.act.ExecutionVariableDeleteCmd;
import com.github.sparkzxl.workflow.infrastructure.act.SetFlowNodeAndGoCmd;
import com.github.sparkzxl.workflow.infrastructure.constant.WorkflowConstants;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtHiTaskStatus;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtProcessStatus;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtProcessTaskRule;
import com.github.sparkzxl.workflow.infrastructure.entity.ExtProcessUser;
import com.github.sparkzxl.workflow.infrastructure.enums.ProcessStatusEnum;
import com.github.sparkzxl.workflow.infrastructure.enums.TaskStatusEnum;
import com.github.sparkzxl.workflow.infrastructure.utils.ActivitiUtils;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.activiti.bpmn.model.Process;
import org.activiti.bpmn.model.*;
import org.activiti.engine.IdentityService;
import org.activiti.engine.ManagementService;
import org.activiti.engine.impl.identity.Authentication;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * description: ????????????API??????
 *
 * @author charles.zhou
 * @date 2020-07-20 18:35:56
 */
@Service
@Slf4j
public class ActWorkApiService {

    @Autowired
    private IProcessTaskService processTaskService;
    @Autowired
    private IProcessRuntimeService processRuntimeService;
    @Autowired
    private IExtProcessStatusService processTaskStatusService;
    @Autowired
    private IExtHiTaskStatusService actHiTaskStatusService;
    @Autowired
    private IProcessRepositoryService repositoryService;
    @Autowired
    private ManagementService managementService;
    @Autowired
    private IExtProcessTaskRuleService processTaskRuleService;
    @Autowired
    private IExtProcessUserRepository processUserRepository;
    @Autowired
    private IdentityService identityService;

    @Transactional(rollbackFor = Exception.class)
    public DriverResult startProcess(DriveProcess driveProcess) {
        DriverResult driverResult = new DriverResult();
        String businessId = driveProcess.getBusinessId();
        try {
            String userId = driveProcess.getUserId();
            //?????????????????????????????????????????????????????????????????????????????????
            ProcessInstance originalProcessInstance = processRuntimeService.getProcessInstanceByBusinessId(businessId);
            if (ObjectUtils.isNotEmpty(originalProcessInstance)) {
                BizExceptionAssert.businessFail("????????????????????????????????????");
            }
            Map<String, Object> variables = Maps.newHashMap();
            variables.put("assignee", driveProcess.getNextTaskApproveUserId());
            variables.put("actType", driveProcess.getActType());
            identityService.setAuthenticatedUserId(String.valueOf(userId));
            ProcessInstance processInstance = processRuntimeService.startProcessInstanceByKey(driveProcess.getProcessDefinitionKey(),
                    businessId,
                    variables);
            String processInstanceId = processInstance.getProcessInstanceId();
            String comment = driveProcess.getComment();
            if (StringUtils.isEmpty(comment)) {
                comment = "??????????????????";
            }
            ExtProcessUser processUser = processUserRepository.findUserById(userId);
            String processName = "???".concat(processUser.getName()).concat("?????????").concat(processInstance.getProcessDefinitionName());
            log.info("??????activiti??????------++++++ProcessInstanceId???{}------++++++", processInstanceId);
            log.info(processName);
            boolean needJump = driveProcess.isNeedJump();
            if (needJump) {
                driveProcess.setProcessInstanceId(processInstanceId);
                driveProcess.setProcessDefinitionKey(processInstance.getProcessDefinitionKey());
                driveProcess.setProcessName(processName);
                driveProcess.setActType(WorkflowConstants.WorkflowAction.JUMP);
                driveProcess.setComment(comment);
                driverResult = jumpProcess(driveProcess, processName);
            } else {
                variables.put("actType", WorkflowConstants.WorkflowAction.SUBMIT);
                DriverData driverData = DriverData.builder()
                        .userId(userId)
                        .processInstanceId(processInstanceId)
                        .processName(processName)
                        .businessId(businessId)
                        .processDefinitionKey(processInstance.getProcessDefinitionKey())
                        .actType(WorkflowConstants.WorkflowAction.SUBMIT)
                        .comment(comment)
                        .variables(variables)
                        .build();
                driverResult = promoteProcess(driverData);
            }
        } catch (Exception e) {
            driverResult.setErrorMsg(e.getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.error("???????????? Exception???{}", ExceptionUtil.getMessage(e));
        }
        return driverResult;
    }

    public DriverResult promoteProcess(DriverData driverData) {
        String processInstanceId = driverData.getProcessInstanceId();
        String comment = driverData.getComment();
        String userId = driverData.getUserId();
        Map<String, Object> variables = driverData.getVariables();
        Task task = processTaskService.getLatestTaskByProInstId(processInstanceId);
        String currentTaskId = task.getId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        ApiResponseStatus.FAILURE.assertNotNull(task);
        validateAuthority(task, userId);
        //???????????????
        Authentication.setAuthenticatedUserId(userId);
        if (StringUtils.isNotEmpty(comment)) {
            processTaskService.addComment(currentTaskId, processInstanceId, comment);
        }
        processTaskService.setAssignee(currentTaskId, userId);
        processTaskService.claimTask(currentTaskId, userId);
        processTaskService.completeTask(currentTaskId, variables);
        return recordProcessState(processInstanceId, driverData.getProcessName(), driverData.getBusinessId(), driverData.getActType(), currentTaskId, taskDefinitionKey);
    }

    /**
     * ??????????????????
     *
     * @param processInstanceId ????????????id
     * @param businessId        ????????????
     * @param actType           ??????????????????
     * @param currentTaskId     ??????id
     * @param taskDefinitionKey ????????????key
     * @return DriverResult
     */
    public DriverResult recordProcessState(String processInstanceId, String processName, String businessId,
                                           int actType, String currentTaskId,
                                           String taskDefinitionKey) {
        boolean processIsEnd = processRuntimeService.processIsEnd(processInstanceId);
        String status;
        if (processIsEnd) {
            status = ProcessStatusEnum.END.getDesc();
        } else {
            status = ProcessStatusEnum.RUN_TIME.getDesc();
        }
        DriverResult driverResult = new DriverResult();
        driverResult.setProcessIsEnd(processIsEnd);
        CompletableFuture.runAsync(() -> saveProcessTaskStatus(
                processInstanceId,
                processName,
                businessId,
                status));
        CompletableFuture.runAsync(() -> saveExtHiTaskStatus(processInstanceId,
                currentTaskId, taskDefinitionKey, TaskStatusEnum.get(actType)));
        driverResult.setOperateSuccess(true);
        return driverResult;
    }

    @Transactional(rollbackFor = Exception.class)
    public DriverResult jumpProcess(DriveProcess driveProcess, String processName) {
        DriverResult driverResult = new DriverResult();
        try {
            String businessId = driveProcess.getBusinessId();
            String userId = driveProcess.getUserId();
            int actType = driveProcess.getActType();
            ProcessInstance processInstance = processRuntimeService.getProcessInstanceByBusinessId(businessId);
            if (ObjectUtils.isEmpty(processInstance)) {
                BizExceptionAssert.businessFail("????????????????????????????????????????????????");
            }
            String processInstanceId = processInstance.getProcessInstanceId();
            String comment = driveProcess.getComment();
            Task currentTask = processTaskService.getLatestTaskByProInstId(processInstanceId);
            String currentTaskId = currentTask.getId();
            //???????????????
            Authentication.setAuthenticatedUserId(userId);
            if (StringUtils.isNotEmpty(comment)) {
                processTaskService.addComment(currentTaskId, processInstanceId, comment);
            }
            processTaskService.setAssignee(currentTaskId, userId);
            String taskDefinitionKey = currentTask.getTaskDefinitionKey();
            String processDefinitionId = currentTask.getProcessDefinitionId();
            BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
            // ??????????????????
            Process process = bpmnModel.getMainProcess();
            //???????????????FlowElement??????
            Collection<FlowElement> flowElements = process.getFlowElements();
            // ??????????????????task??????key
            ExtProcessTaskRule actRuTaskRule = processTaskRuleService.findActRuTaskRule(driveProcess.getProcessDefinitionKey(),
                    taskDefinitionKey, driveProcess.getActType());
            if (ObjectUtils.isEmpty(actRuTaskRule)) {
                BizExceptionAssert.businessFail("???????????????????????????");
            }
            String taskDefKey = actRuTaskRule.getTaskDefKey();
            FlowElement flowElement = ActivitiUtils.getFlowElementById(taskDefKey, flowElements);
            // ????????????????????????
            assert flowElement != null;
            FlowNode targetNode = (FlowNode) process.getFlowElement(flowElement.getId());
            // ?????????????????????????????????????????????id??????id?????????????????????????????????
            String executionEntityId = managementService.executeCommand(new DeleteTaskCmd(currentTaskId));
            //????????????
            managementService.executeCommand(new ExecutionVariableDeleteCmd(executionEntityId));
            // ???????????????????????????
            managementService.executeCommand(new SetFlowNodeAndGoCmd(targetNode, executionEntityId));
            driverResult = recordProcessState(processInstanceId, processName, driveProcess.getBusinessId(), actType, currentTaskId, taskDefinitionKey);
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            driverResult.setErrorMsg(e.getMessage());
            log.error("???????????? Exception???{}", ExceptionUtil.getMessage(e));
        }
        return driverResult;
    }

    /**
     * ????????????????????????
     *
     * @param processInstanceId ????????????id
     * @param taskId            ??????id
     * @param taskDefinitionKey ????????????key
     * @param taskStatus        ????????????
     */
    public void saveExtHiTaskStatus(String processInstanceId, String taskId,
                                    String taskDefinitionKey, TaskStatusEnum taskStatus) {
        log.info("???????????????????????? processInstanceId???{}???taskId???{}", processInstanceId, taskId);
        ExtHiTaskStatus actHiTaskStatus = new ExtHiTaskStatus();
        actHiTaskStatus.setProcessInstanceId(processInstanceId);
        actHiTaskStatus.setTaskId(taskId);
        actHiTaskStatus.setTaskDefKey(taskDefinitionKey);
        actHiTaskStatus.setTaskStatus(taskStatus.getCode());
        actHiTaskStatus.setTaskStatusName(taskStatus.getDesc());
        actHiTaskStatusService.save(actHiTaskStatus);
    }

    /**
     * ??????????????????????????????
     *
     * @param processInstanceId ????????????id
     * @param status            ????????????
     */
    public void saveProcessTaskStatus(String processInstanceId, String processName, String businessId, String status) {
        log.info("?????????????????????????????? processInstanceId???{}???businessId???{}", processInstanceId, businessId);
        ExtProcessStatus extProcessStatus = processTaskStatusService.getExtProcessStatus(businessId, processInstanceId);
        //??????????????????????????????
        if (!ObjectUtils.isNotEmpty(extProcessStatus)) {
            extProcessStatus = new ExtProcessStatus();
            extProcessStatus.setProcessInstanceId(processInstanceId);
            extProcessStatus.setBusinessId(businessId);
            extProcessStatus.setProcessName(processName);
        }
        extProcessStatus.setStatus(status);
        processTaskStatusService.saveOrUpdate(extProcessStatus);
    }

    @Transactional(rollbackFor = Exception.class)
    public DriverResult submitProcess(DriveProcess driveProcess) {
        DriverResult driverResult = new DriverResult();
        String businessId = driveProcess.getBusinessId();
        try {
            String nextTaskApproveUserId = driveProcess.getNextTaskApproveUserId();
            String userId = driveProcess.getUserId();
            Map<String, Object> variables = Maps.newHashMap();
            if (StringUtils.isNotEmpty(nextTaskApproveUserId)) {
                variables.put("assignee", nextTaskApproveUserId);
            }
            variables.put("actType", driveProcess.getActType());
            ProcessInstance processInstance = processRuntimeService.getProcessInstanceByBusinessId(businessId);
            if (ObjectUtils.isEmpty(processInstance)) {
                BizExceptionAssert.businessFail("????????????????????????????????????????????????");
            }
            DriverData driverData = DriverData.builder()
                    .userId(userId)
                    .processInstanceId(processInstance.getProcessInstanceId())
                    .businessId(businessId)
                    .processDefinitionKey(processInstance.getProcessDefinitionKey())
                    .actType(driveProcess.getActType())
                    .comment(driveProcess.getComment())
                    .variables(variables)
                    .build();
            driverResult = promoteProcess(driverData);
        } catch (Exception e) {
            e.printStackTrace();
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.error("???????????? Exception???{}", ExceptionUtil.getMessage(e));
            driverResult.setErrorMsg(e.getMessage());
        }
        return driverResult;
    }


    private void validateAuthority(Task task, String userId) {
        FlowElement flowElement = getThisNodeByInsId(task);
        if (flowElement instanceof UserTask) {
            List<String> listGroup = ((UserTask) flowElement).getCandidateGroups();
            if (listGroup.size() == 0) {
                return;
            }
            //???????????????
            List<String> userIdList = processUserRepository.findUserIdListByRoleIds(listGroup);
            if (userIdList.contains(userId)) {
                return;
            }
        }
        ExtProcessUser extProcessUser = processUserRepository.findUserById(userId);
        BizExceptionAssert.businessFail("?????????????????????[" + extProcessUser.getName() + "]??????????????????");
    }

    private FlowElement getThisNodeByInsId(Task task) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        // ??????????????????
        Process process = bpmnModel.getMainProcess();
        //???????????????FlowElement??????
        Collection<FlowElement> flowElements = process.getFlowElements();
        return ActivitiUtils.getFlowElementById(task.getTaskDefinitionKey(), flowElements);
    }
}
