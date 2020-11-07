package com.lkt.androidhookdemo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class HookUtil {
    private static final String TAG = "HookUtil";

    public void HookUtil() {

    }

    /**
     * 为了兼容android 10，需要在第一个activity启动时再调用，不能在application的初始化中调用，否则instance会获取不到
     * @throws ClassNotFoundException
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    @SuppressLint("PrivateApi")
    public void hookAm() throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        Log.i(TAG,"hookAm,SDK:" + Build.VERSION.SDK_INT);

        Field gdefaultField;
        Class<?> amClass;
        if (Build.VERSION.SDK_INT > 28) {//android 10
            Class<?> amnClass = Class.forName("android.app.ActivityTaskManager");
            gdefaultField = amnClass.getDeclaredField("IActivityTaskManagerSingleton");
            amClass = Class.forName("android.app.IActivityTaskManager");
        } else if (Build.VERSION.SDK_INT > 25) {//android 8.0、8.1、9.0
            Class<?> amnClass = Class.forName("android.app.ActivityManager");
            gdefaultField = amnClass.getDeclaredField("IActivityManagerSingleton");
            amClass = Class.forName("android.app.IActivityManager");
        } else {
            Class<?> amnClass = Class.forName("android.app.ActivityManagerNative");
            gdefaultField = amnClass.getDeclaredField("gDefault");
            amClass = Class.forName("android.app.IActivityManager");
        }
        gdefaultField.setAccessible(true);
        Object gDefault = gdefaultField.get(null);
        Log.i(TAG,"gDefault:" + gDefault);

        Class<?> singletonClass = Class.forName("android.util.Singleton");
        Field mInstanceField = singletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        Object instance = mInstanceField.get(gDefault);
        Log.i(TAG,"instance:" + instance);

        ActivityManagerHandler activityManagerHandler = new ActivityManagerHandler(instance);
        Object amProxy = Proxy.newProxyInstance(
                ClassLoader.getSystemClassLoader(),
                new Class[]{amClass},
                activityManagerHandler);

        mInstanceField.set(gDefault,amProxy);
    }

    private static class ActivityManagerHandler implements InvocationHandler {
        private Object am;

        public ActivityManagerHandler(Object am) {
            this.am = am;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Log.i(TAG,"call:" + method.getName());
            if ("startActivity".equals(method.getName())) {
                for (Object arg : args) {
                    if (arg instanceof Intent) {
                        Intent intent = (Intent)arg;
                        Log.i(TAG,"action:" + intent.getAction());
                        Log.i(TAG,"data:" + intent.getDataString());
                        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                            Log.d(TAG,"可以拦截启动activity做一些事情");
                        }
                    }
                }
            }
            return method.invoke(am,args);
        }
    }
}
