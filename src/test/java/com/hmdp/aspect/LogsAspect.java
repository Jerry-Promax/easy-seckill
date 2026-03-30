package com.hmdp.aspect;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

/**
 * 类功能描述
 * <p>
 * 作者：jerry
 * 日期：2026-03-27
 */
@Aspect
@Component
@EnableAspectJAutoProxy
public class LogsAspect {
    @Pointcut("execution(* com.hmdp..*.*(..))")
    public void log() {
        System.out.println("日志切面");
    }
    @Before("log()")
    public void before() {
        System.out.println("前置切面");
    }
}