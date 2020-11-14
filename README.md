# AndroidHookDemo
兼容Android4.4到10的hook ActivityManager demo，可以拦截startActivity做一些事情；
# Android使用hook技术拦截startActivity
## 1、hook是什么
hook，就是钩子的意思，可以勾住系统中某一段程序逻辑，在不影响原有流程的情况下，加入自己的代码逻辑，实现一些特殊的需求。
比如你的APP接入了一个广告SDK，然后有些比较流氓的广告H5页面为了给自己家的APP引流，会自动跳转到应用市场下载自己家的APP，然后广告页面还会循环跳转到该APP，有些手机不断弹窗询问是否跳转，这个时候就比较影响我们APP的用户体验了，使用hook技术就可以轻松应对这种情况，进行一些拦截处理。
## 2、hook怎么使用
使用hook前需要掌握一些相关的技能：

 1. java反射，需要掌握类Class，方法Method，成员Field的使用方法；
 2. 代理模式，可以自己编写实现静态代理类，也可以使用Proxy动态代理实现；
 3. 阅读Android源码，hook的切入点都在源码内部，需要先理清源码逻辑，比如activity的启动流程；

当掌握了上面这些基本技能后，还需要知道hook的基本思路：

 1. 根据需求确定要hook的对象，需要通过分析源码来确定，一般选择静态变量和单例，因为静态变量和单例对象比较容易定位，并且相对来说不容易发生变化；
 2. 找到要hook的对象的持有者，通过他拿到要hook的对象，这一步同样是需要分析源码来确定；
 3. 实现要hook的对象的代理类，加入自己的业务逻辑，并创建出该类的对象；
 4. 使用代理类对象替换掉要hook的对象；

## 3、hook startActivity实例分析
想要实现hook startActivity，按照上面说的hook思路，首先就必须先分析源码，了解Activity的启动流程。
启动一个activity非常简单，那startActivity究竟做了什么处理呢？
```java
public void startActivity(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.baidu.com"));
        intent.setAction(Intent.ACTION_VIEW);
        startActivity(intent);
}
```
这里我先以Android6.0的源码来分析，后面还会再讲解不同Android版本之间的差异。
```java
@Override
public void startActivity(Intent intent) {
        this.startActivity(intent, null);
}

@Override
public void startActivity(Intent intent, @Nullable Bundle options) {
        if (options != null) {
            startActivityForResult(intent, -1, options);
        } else {
            // Note we want to go through this call for compatibility with
            // applications that may have overridden the method.
            startActivityForResult(intent, -1);
        }
}

public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        if (mParent == null) {
            Instrumentation.ActivityResult ar =
                mInstrumentation.execStartActivity(
                    this, mMainThread.getApplicationThread(), mToken, this,
                    intent, requestCode, options);
            
            ......
        } else {
            if (options != null) {
                mParent.startActivityFromChild(this, intent, requestCode, options);
            } else {
                // Note we want to go through this method for compatibility with
                // existing applications that may have overridden it.
                mParent.startActivityFromChild(this, intent, requestCode);
            }
        }
    }

```
阅读源码可以发现，启动activity是由Instrumentation的execStartActivity来实现的，
```java
public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        IApplicationThread whoThread = (IApplicationThread) contextThread;
        Uri referrer = target != null ? target.onProvideReferrer() : null;
        if (referrer != null) {
            intent.putExtra(Intent.EXTRA_REFERRER, referrer);
        }
        .....
        try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess();
            int result = ActivityManagerNative.getDefault()
                .startActivity(whoThread, who.getBasePackageName(), intent,
                        intent.resolveTypeIfNeeded(who.getContentResolver()),
                        token, target != null ? target.mEmbeddedID : null,
                        requestCode, 0, null, options);
            checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
        return null;
    }
```
看到这里重点关注ActivityManagerNative.getDefault().startActivity(),首先ActivityManagerNative这类继承了Binder，还实现了IActivityManager接口。
```java
public abstract class ActivityManagerNative extends Binder implements IActivityManager
```
启动activity的过程其实是一个Android典型的跨进程通信的过程，ActivityManagerNative就是Stub类，负责与服务端进程的ActivityManagerService通信，最终启动Activity的也是通过远端服务ActivityManagerService来启动的。
```java
static public IActivityManager getDefault() {
    return gDefault.get();
}

private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
        protected IActivityManager create() {
            IBinder b = ServiceManager.getService("activity");
            if (false) {
                Log.v("ActivityManager", "default service binder = " + b);
            }
            IActivityManager am = asInterface(b);
            if (false) {
                Log.v("ActivityManager", "default service = " + am);
            }
            return am;
        }
    };
```
ActivityManagerNative.getDefault()获取的是一个IActivityManager对象，gDefalut本身是通Singleton实现的单例模式，从源码中可以看到，先从ServiceManager中获取到AMS远端服务的Binder对象，然后使用asInterface方法转化成本地化对象，最后是调用这个IActivityManager对象startActivity去启动Activity的，分析到这里，基本就可以确定，IActivityManager对象就是我们要hook点，gDefault又是静态又是单例的，也非常符合hook的原则。
##4、hook startActivity实现
代码都有注释，可以结合上面的分析去理解就可以了。重点要关注版本兼容的问题，由于不同Android版本对startActivity的实现流程有差异，所以实现的时候需要根据Android版本做兼容处理，否则可能会出现一些奔溃问题，影响APP的稳定性。
```java
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        HookUtil hookUtil = new HookUtil();
        try {
            hookUtil.hookAm();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public void startActivity(View view) {
        Log.d(TAG,"启动activity");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.baidu.com"));
        intent.setAction(Intent.ACTION_VIEW);
        startActivity(intent);
    }
}
```
```java
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
        Class<?> amClass;//最终想要拿到的对象类型
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

        //先拿到gDefault对象，gDefault是static的，所以传null就可以
        gdefaultField.setAccessible(true);
        Object gDefault = gdefaultField.get(null);
        Log.i(TAG,"gDefault:" + gDefault);

        //反射Singleton
        Class<?> singletonClass = Class.forName("android.util.Singleton");
        Field mInstanceField = singletonClass.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        //这里拿到IActivityManager对象，Android 10是IActivityTaskManager
        Object instance = mInstanceField.get(gDefault);
        Log.i(TAG,"instance:" + instance);

        //动态代理，用amProxy代理对象，替换掉原本的IActivityManager对象
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
```
前面分析了Android 6.0的源码，下面我们来看看Android8.0和Android10.0的情况。
Android8.0的Instrumentation的execStartActivity中，变成了ActivityManager.getService().startActivity，而getService中是通过IActivityManagerSingleton单例对象拿到IActivityManager对象。
```java
public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        ...
        try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess(who);
            int result = ActivityManager.getService()
                .startActivity(whoThread, who.getBasePackageName(), intent,
                        intent.resolveTypeIfNeeded(who.getContentResolver()),
                        token, target != null ? target.mEmbeddedID : null,
                        requestCode, 0, null, options);
            checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
        return null;
    }
```
```java
public static IActivityManager getService() {
        return IActivityManagerSingleton.get();
    }

    private static final Singleton<IActivityManager> IActivityManagerSingleton =
            new Singleton<IActivityManager>() {
                @Override
                protected IActivityManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                    final IActivityManager am = IActivityManager.Stub.asInterface(b);
                    return am;
                }
            };
```
Android10 Instrumentation的execStartActivity中，变成了ActivityTaskManager.getService().startActivity，而getService中是通过IActivityTaskManagerSingleton单例对象拿到IActivityTaskManager对象。
```java
public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        
        ...
        try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess(who);
            int result = ActivityTaskManager.getService()
                .startActivity(whoThread, who.getBasePackageName(), intent,
                        intent.resolveTypeIfNeeded(who.getContentResolver()),
                        token, target != null ? target.mEmbeddedID : null,
                        requestCode, 0, null, options);
            checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
        return null;
    }
```
```java
public static IActivityTaskManager getService() {
        return IActivityTaskManagerSingleton.get();
    }

    @UnsupportedAppUsage(trackingBug = 129726065)
    private static final Singleton<IActivityTaskManager> IActivityTaskManagerSingleton =
            new Singleton<IActivityTaskManager>() {
                @Override
                protected IActivityTaskManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE);
                    return IActivityTaskManager.Stub.asInterface(b);
                }
            };
```
结合前面Android6.0的分析，只要把相应要反射的类和字段名做替换就可以了。需要注意的是，在Android10的情况下，如果在Application的onCreate中去执行hook代码，会出现IActivityTaskManagerSingleton还没有初始化的情况，也就是它mInstance为null，所以必须在MainActivity的onCreate中去执行hook代码，也可以在子线程中去执行，不在Application中执行也有个好处是不影响应用的启动时间，而且我们也不需要去拦截应用的MainActivity启动过程。
##5、hook应用场景

 1. 拦截广告SDK或者其他SDK的流氓跳转行为，可以通过后台配置黑名单，根据启动的URI做拦截；
 2. 启动没有在AndroidManifest中声明的Activity，这个思路大概是先在AndroidManifest声明一个代理Activity，当我们要启动目标Activity时，通过hook拦截startActivity，伪造一个代理Activity的Intent，这个Intent能通过ActivityManagerService检验，然后在hook掉ActivityThread中handle对象的mCallBack，在handleMessage中把Intent中要启动的Activity再换回目标Activity，这样就能顺利启动了。

