package com.hmdp.utils;
// Interface for implementing lock mechanism
public interface ILock {
    boolean tryLock(long timeout);
    void unlock();
}
