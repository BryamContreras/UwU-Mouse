package dev.mobimapper.mobimapper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.profile.ProfileCodec

/** Android's document picker owns filenames, destinations and grants; profiles remain plain JSON. */
class ProfileFileActivity:Activity() {
    private var document:String?=null
    private var selectedUri:Uri?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        GameCoordinator.initialize(applicationContext)
        document=state?.getString("document")
        selectedUri=state?.getString("selectedUri")?.let(Uri::parse)
        selectedUri?.let { processDocument(it);return }
        if(state!=null) return
        try {
            val id=intent.getStringExtra("exportId")
            val picker=if(id!=null) {
                val (name,json)=GameCoordinator.exportProfileFile(id);document=json
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE,name)
            } else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/plain","application/octet-stream"))
            @Suppress("DEPRECATION")
            startActivityForResult(picker,51)
        } catch(failure:Exception) { fail(failure) }
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("document",document);out.putString("selectedUri",selectedUri?.toString());super.onSaveInstanceState(out)
    }
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=51) return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null) { setResult(RESULT_CANCELED);finish();return }
        selectedUri=uri;processDocument(uri)
    }
    private fun processDocument(uri:Uri) {
        val json=document
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            addView(ProgressBar(this@ProfileFileActivity))
            addView(TextView(this@ProfileFileActivity).apply { text=if(json!=null) "Guardando perfil…" else "Importando perfil…";setPadding(24,24,24,24) })
        })
        Thread({
            val outcome=runCatching {
                if(json!=null) {
                    requireNotNull(contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                    null
                } else {
                    val bytes=requireNotNull(contentResolver.openInputStream(uri)).use { it.readNBytes(ProfileCodec.MAX_DOCUMENT_CHARS+1) }
                    require(bytes.size<=ProfileCodec.MAX_DOCUMENT_CHARS) { "Archivo de perfil demasiado grande" }
                    ProfileCodec.decodeImportPayload(java.util.Base64.getEncoder().encodeToString(bytes))
                }
            }
            runOnUiThread {
                if(isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val imported=outcome.getOrThrow()
                    val count=imported?.let(GameCoordinator::importUserProfileFile) ?: 0
                    Toast.makeText(this,if(imported==null) "Perfil exportado" else if(count==1) "Perfil importado" else "$count perfiles importados",Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK);finish()
                } catch(failure:Exception) { fail(failure) }
            }
        },"UwUProfileFile").start()
    }
    private fun fail(failure:Exception) {
        val message=failure.message ?: "No se pudo abrir el archivo de perfil"
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED,Intent().putExtra("error",message));finish()
    }
}
