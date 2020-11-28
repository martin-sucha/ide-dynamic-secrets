package com.github.martin-sucha.idedynamicsecrets.services

import com.intellij.openapi.project.Project
import com.github.martin-sucha.idedynamicsecrets.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
