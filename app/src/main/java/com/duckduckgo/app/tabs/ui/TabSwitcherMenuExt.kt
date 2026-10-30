<?xml version="1.0" encoding="utf-8"?>
<!--
  ~ Copyright (c) 2023 DuckDuckGo
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    tools:ignore="AlwaysShowAction">

    <!-- 1. INVISIBLE SPACER (Pushes the + button to the center) -->
    <item
        android:id="@+id/dummyLeftSpacer"
        android:icon="@drawable/ic_menu_vertical_24"
        android:title=""
        android:visible="invisible"
        app:showAsAction="always" />

    <!-- 2. CENTER: The Add (+) Button -->
    <item
        android:id="@+id/newTabToolbarButton"
        android:icon="@drawable/ic_add_24"
        android:title="@string/newTabMenuItem"
        app:showAsAction="always" />

    <!-- 3. RIGHT: The 3-Dots Menu -->
    <item
        android:id="@+id/popupMenuToolbarButton"
        android:icon="@drawable/ic_menu_vertical_24"
        android:title="@string/options"
        app:showAsAction="always" />

</menu>