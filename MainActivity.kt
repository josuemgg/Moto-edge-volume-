package com.edgevolume

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.edgevolume.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        // Botón de permiso
        binding.btnPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Switch de activar/desactivar el servicio
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startEdgeService()
                    binding.tvStatus.text = "✅ Borde activo — desliza para controlar el volumen"
                } else {
                    binding.switchService.isChecked = false
                    binding.tvStatus.text = "⚠️ Primero concede el permiso de superposición"
                }
            } else {
                stopEdgeService()
                binding.tvStatus.text = "⏸ Servicio detenido"
            }
        }

        // Selector de lado (derecho o izquierdo)
        binding.radioGroupSide.setOnCheckedChangeListener { _, checkedId ->
            val side = if (checkedId == R.id.radioRight) "right" else "left"
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putString("side", side).apply()
            // Reiniciar el servicio si está activo
            if (binding.switchService.isChecked) {
                stopEdgeService()
                startEdgeService()
            }
        }

        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasPermission = Settings.canDrawOverlays(this)
        if (hasPermission) {
            binding.tvPermissionStatus.text = "✅ Permiso de superposición: Concedido"
            binding.btnPermission.isEnabled = false
            binding.btnPermission.alpha = 0.5f
            binding.switchService.isEnabled = true
        } else {
            binding.tvPermissionStatus.text = "❌ Permiso de superposición: Requerido"
            binding.btnPermission.isEnabled = true
            binding.btnPermission.alpha = 1f
            binding.switchService.isEnabled = false
            binding.switchService.isChecked = false
        }
    }

    private fun startEdgeService() {
        val intent = Intent(this, EdgeVolumeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopEdgeService() {
        stopService(Intent(this, EdgeVolumeService::class.java))
    }
}
