package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

public class UserHolder {
    //这边新建一个threadlocal对象,下面就可以搞多个set方法,来存不同的值
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        // 调用这个set方法的时候, java会根据当前线程找到其对应的 ThreadLocalMap，并将键值对存储在其中。
        // 这边的set只要设置value就好了, ∵key是默认的当前的threadlocal
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }


}
