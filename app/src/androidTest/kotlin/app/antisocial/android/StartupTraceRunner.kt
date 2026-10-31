/* anti social, GPL-3.0-or-later. Explicit private startup diagnostics only. */
package app.antisocial.android

import android.app.Application
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import org.json.JSONObject

/** Observe Application.onCreate without changing the shipping manifest or
 * substituting a test Application/Repository. Content-provider/bootstrap work
 * preceding this framework callback is outside the method trace. */
class StartupTraceRunner:AndroidJUnitRunner() {
    private var requested=false
    private var tracing=false
    private var began=0L
    private var output:File?=null
    private val events=mutableListOf<JSONObject>()
    override fun onCreate(arguments:Bundle?) {
        requested=arguments?.getString("antisocialIsolatedFixtureDevice")=="true"&&
            arguments.getString("antisocialPrivateStartupTrace")=="true"
        super.onCreate(arguments)
    }
    override fun callApplicationOnCreate(app:Application) {
        if(requested) {
            check(app.packageName=="app.antisocial.android")
            output=File(app.getExternalFilesDir("validation"),"whole-process-startup").apply {mkdirs()}
            began=SystemClock.elapsedRealtime()
            Debug.startMethodTracingSampling(File(output,"application-start.trace").path,32*1024*1024,2_000)
            tracing=true;event("application-enter")
        }
        try {super.callApplicationOnCreate(app);if(requested)event("application-return")}
        catch(failure:Throwable){stopTrace();throw failure}
    }
    @Synchronized internal fun event(stage:String,values:Map<String,Any> = emptyMap()) {
        val directory=checkNotNull(output){"The explicit startup trace runner is required"}
        val row=JSONObject().put("stage",stage).put("atMs",SystemClock.elapsedRealtime()-began)
        values.forEach {(key,value)->row.put(key,value)};events+=row
        File(directory,"events.json").writeText(org.json.JSONArray(events).toString(2))
    }
    @Synchronized internal fun stopTrace() {
        if(tracing){tracing=false;Debug.stopMethodTracing()}
    }
    override fun finish(resultCode:Int,results:Bundle?) {
        try {stopTrace()}finally {super.finish(resultCode,results)}
    }
}
