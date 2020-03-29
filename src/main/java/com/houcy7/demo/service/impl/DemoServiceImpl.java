package com.houcy7.demo.service.impl;

import com.houcy7.demo.service.DemoService;
import com.houcy7.framework.annotation.GPService;

import java.text.SimpleDateFormat;
import java.util.Date;

@GPService
public class DemoServiceImpl implements DemoService {

    @Override
    public String time() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return "Current time is : " + simpleDateFormat.format(new Date());
    }

    @Override
    public String name(String name) {
        return "My name is : " + name;
    }

    @Override
    public String plus(Integer a, Integer b) {
        return String.format("%d + %d = %d", a , b, a + b);
    }
}
