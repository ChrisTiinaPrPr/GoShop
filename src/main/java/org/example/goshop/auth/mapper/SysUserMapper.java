package org.example.goshop.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.goshop.auth.entity.SysUser;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
