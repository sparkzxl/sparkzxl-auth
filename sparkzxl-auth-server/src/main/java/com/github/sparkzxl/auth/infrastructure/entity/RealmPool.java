package com.github.sparkzxl.auth.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.sparkzxl.database.entity.Entity;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * description: 领域池信息
 *
 * @author charles.zhou
 * @date 2021-02-02 16:08:05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("realm_pool")
@ApiModel(value = "领域池信息对象", description = "")
public class RealmPool extends Entity<Long> {

    private static final long serialVersionUID = -6955056237245642400L;

    @ApiModelProperty(value = "领域用户id")
    @TableField("realm_user_id")
    private Long realmUserId;

    @ApiModelProperty(value = "领域池编码")
    @TableField("code")
    private String code;

    @ApiModelProperty(value = "领域池名称")
    @TableField("name")
    private String name;

    @ApiModelProperty(value = "状态")
    @TableField("status")
    private Boolean status;

    @ApiModelProperty(value = "logo地址")
    @TableField("logo")
    private String logo;

    @ApiModelProperty(value = "领域池简介")
    @TableField("describe_")
    private String describe;

    @ApiModelProperty(value = "用户数")
    @TableField(exist = false)
    private int userCount;

}
