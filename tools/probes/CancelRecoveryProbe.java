import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

/** Diagnostic of the recorded 0.1.0 fixture, not the product recovery path. Never creates DOWN. */
public final class CancelRecoveryProbe {
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 1) throw new SecurityException();
        long downTime = Long.parseLong(args[0]);
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
        Object tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object info = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(tasks);
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        if (!"dev.mobimapper.inputlab".equals(top.getPackageName()) || info.getClass().getField("displayId").getInt(info) != 0) throw new SecurityException("Lab must remain focused");
        int uid = ctx.getPackageManager().getPackageUid("dev.mobimapper.inputlab", 0);
        Object manager = ctx.getSystemService(Context.INPUT_SERVICE);
        float[] xs = {324f,712.8f,885.6f}, ys = {1528.56f,1111.68f,1899.12f};
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[3];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[3];
        for(int i=0;i<3;i++) {
            props[i]=new MotionEvent.PointerProperties(); props[i].id=i; props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords(); coords[i].x=xs[i]; coords[i].y=ys[i]; coords[i].pressure=1; coords[i].size=1;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(downTime,now,MotionEvent.ACTION_CANCEL,3,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,32);
        try {
            InputEvent.class.getMethod("setDisplayId",int.class).invoke(event,0);
            Log.i("MobiRecoveryProbe","cancel attempt pid="+Process.myPid()+" uptime="+now+" down="+downTime+" ids=0,1,2 target="+uid+" display=0 source=4098 device=0 mode=2");
            boolean ok=(Boolean)manager.getClass().getMethod("injectInputEvent",InputEvent.class,int.class,int.class).invoke(manager,event,2,uid);
            Log.i("MobiRecoveryProbe","cancel result="+ok+" uptime="+SystemClock.uptimeMillis());
            System.out.println("cancelAdmitted="+ok);
        } finally { event.recycle(); }
    }
}
