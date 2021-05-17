package com.github.sparkzxl.workflow.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * description:挂起流程入参
 *
 * @author charles.zhou
 * @date 2020-10-01 19:52:24
 */
@Data
@ApiModel("挂起流程入参")
public class SuspendProcessDTO {

    @ApiModelProperty("业务主键")
    private String businessId;

    @ApiModelProperty("流程实例id")
    private String processInstanceId;

    @ApiModelProperty("挂起类型：1.业务删除 2. 实例删除")
    @NotNull(message = "流程挂起类型不能为空")
    private Integer type;
}
