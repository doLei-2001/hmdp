package com.hmdp.dto;

import com.hmdp.entity.User;
import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;

    public UserDTO userdto2User(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setNickName(user.getNickName());
        userDTO.setId(user.getId());
        userDTO.setIcon(user.getIcon());

        return userDTO;
    }
}
