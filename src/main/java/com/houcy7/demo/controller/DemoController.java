package com.houcy7.demo.controller;

import com.houcy7.demo.service.DemoService;
import com.houcy7.framework.annotation.GPAutowired;
import com.houcy7.framework.annotation.GPController;
import com.houcy7.framework.annotation.GPRequestMapping;
import com.houcy7.framework.annotation.GPRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@GPController("/")
public class DemoController {

    @GPAutowired
    private DemoService demoService;

    @GPRequestMapping("/time")
    public String time(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        return demoService.time();
    }

    @GPRequestMapping("/name")
    public String name(HttpServletRequest req, HttpServletResponse resp, @GPRequestParam("name") String name) throws IOException {
//
        return demoService.name(name);
    }

    @GPRequestMapping("/plus")
    public String plus( @GPRequestParam("a") Integer a,  @GPRequestParam("b") Integer b) throws IOException {
       return demoService.plus(a, b);
    }

    @GPRequestMapping("/edit.*")
    public String edit(HttpServletRequest request) throws IOException {
        String requestURI = request.getRequestURI();
        return "正则匹配: " + requestURI;
    }




}
