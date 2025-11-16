package com.example.fyp.camera

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.fyp.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ScanChooserSheet : BottomSheetDialogFragment() {

    interface Callbacks {
        fun onPickCamera()
        fun onPickGallery()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.activity_scan_chooser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            (activity as? Callbacks)?.onPickCamera()
            dismiss()
        }
        view.findViewById<View>(R.id.btnPhotos).setOnClickListener {
            (activity as? Callbacks)?.onPickGallery()
            dismiss()
        }
    }

    companion object {
        fun show(host: Activity) {
            val fm = (host as androidx.appcompat.app.AppCompatActivity).supportFragmentManager
            ScanChooserSheet().show(fm, "scanChooser")
        }
    }
}
