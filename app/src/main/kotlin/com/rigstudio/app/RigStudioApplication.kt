package com.rigstudio.app

import android.app.Application
import android.content.Context
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.app.data.ProjectStore
import com.rigstudio.app.export.ExportRunner
import com.rigstudio.app.pipeline.SheetImporter
import com.rigstudio.app.art.TemplateArt

/**
 * Composition root.
 *
 * RigStudio has no DI framework: the object graph is a handful of singletons built from the
 * application context, which keeps startup instant and the dependency list empty. Every
 * collaborator is offline by construction — nothing here opens a socket, because the app does
 * not declare the INTERNET permission at all.
 */
class RigStudioApplication : Application() {

    val store: ProjectStore by lazy { ProjectStore(this) }

    val importer: SheetImporter by lazy { SheetImporter(this, store) }

    val exportRunner: ExportRunner by lazy { ExportRunner(this, store) }

    val templateArt: TemplateArt by lazy { TemplateArt(this) }

    override fun onCreate() {
        super.onCreate()
        // Fails fast (and in the log) if a coordinate edit ever breaks the sheet layout, instead
        // of silently mis-extracting somebody's character.
        val templateProblems = CharacterSheetTemplate.selfCheck()
        check(templateProblems.isEmpty()) {
            "Character sheet template is invalid: $templateProblems"
        }
        store.ensureLayout()
    }
}

/** Convenience for `context.applicationContext as RigStudioApplication` in ViewModels. */
val Context.app: RigStudioApplication
    get() = applicationContext as RigStudioApplication
