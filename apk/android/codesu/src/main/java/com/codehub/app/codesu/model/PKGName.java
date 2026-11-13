package com.codehub.app.codesu.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "excludedPkg", primaryKeys = {"name"})
public class PKGName {
    @NonNull
    public String name = "";
}
