package com.github.martinsucha.idedynamicsecrets.services

import com.intellij.openapi.project.Project
import com.github.martinsucha.idedynamicsecrets.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
