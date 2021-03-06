package com.github.sparkzxl.workflow.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.github.sparkzxl.constant.EntityConstant;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * description: 流程用户信息
 *
 * @author charles.zhou
 * @date 2021-01-08 16:47:21
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ext_process_user")
@ApiModel(value = "ExtProcessUser对象", description = "")
public class ExtProcessUser implements Serializable {

    private static final long serialVersionUID = -1820198557795317460L;

    @ApiModelProperty(value = "主键")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @ApiModelProperty(value = "用户账户")
    @TableField("account")
    private String account;

    @ApiModelProperty(value = "用户姓名")
    @TableField("name")
    private String name;

    @ApiModelProperty(value = "状态 1启用 0禁用")
    @TableField("status")
    private Boolean status;

    @TableField(value = EntityConstant.COLUMN_CREATE_TIME, fill = FieldFill.INSERT)
    protected LocalDateTime createTime;

    @TableField(value = EntityConstant.COLUMN_UPDATE_TIME, fill = FieldFill.INSERT_UPDATE)
    protected LocalDateTime updateTime;

}
