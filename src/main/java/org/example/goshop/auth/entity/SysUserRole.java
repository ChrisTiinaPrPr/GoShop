package org.example.goshop.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户角色关联。
 *
 * <p>一个用户可以同时拥有 USER 和 MERCHANT。表的联合主键由数据库维护，
 * 因此实体不声明单字段 TableId。</p>
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {
    private Long userId;
    private String role;
}
