package com.proofix.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ProjectCreateRequest {

    @NotBlank(message = "Project name is required")
    @Size(max = 150, message = "Project name must not exceed 150 characters")
    private String projectName;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    public ProjectCreateRequest(){

    }
    public String getProjectName(){
        return projectName;
    }

    public void setProjectName(String projectName){
        this.projectName = projectName;
    }
    public String getDescription(){
        return description;
    }   

    public void setDescription(String description){
        this.description = description;
    }

    
    
}
