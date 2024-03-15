package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_type")
public class ShopType implements Serializable {

    private static final long serialVersionUID = 1L;

    public ShopType(Long id, String name, String icon, Integer sort, LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.sort = sort;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 类型名称
     */
    private String name;

    /**
     * 图标
     */
    private String icon;

    /**
     * 顺序
     */
    private Integer sort;

    /**
     * 创建时间
     */
    @JsonIgnore
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonIgnore
    private LocalDateTime updateTime;


    public static ShopType fromString(String str) {
        int idStart = str.indexOf("id=") + 3;
        int idEnd = str.indexOf(", name");
        Long id = Long.parseLong(str.substring(idStart, idEnd).trim());

        int nameStart = str.indexOf("name=") + 5;
        int nameEnd = str.indexOf(", icon");
        String name = str.substring(nameStart, nameEnd).trim();

        int iconStart = str.indexOf("icon=") + 6;
        int iconEnd = str.indexOf(", sort");
        String icon = str.substring(iconStart, iconEnd).trim();

        int sortStart = str.indexOf("sort=") + 5;
        int sortEnd = str.indexOf(", createTime");
        Integer sort = Integer.parseInt(str.substring(sortStart, sortEnd).trim());

        int createTimeStart = str.indexOf("createTime=") + 11;
        int createTimeEnd = str.indexOf(", updateTime");
        LocalDateTime createTime = LocalDateTime.parse(str.substring(createTimeStart, createTimeEnd).trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        int updateTimeStart = str.indexOf("updateTime=") + 11;
        int updateTimeEnd = str.indexOf(")");
        LocalDateTime updateTime = LocalDateTime.parse(str.substring(updateTimeStart, updateTimeEnd).trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        return new ShopType(id, name, icon, sort, createTime, updateTime);
    }


}
