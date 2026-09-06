package com.genvn.api;

public class CreationQueueFullException extends RuntimeException {
    public CreationQueueFullException() {
        super("开局任务较多，请稍后再试。已提交的故事仍会继续准备。");
    }
}
