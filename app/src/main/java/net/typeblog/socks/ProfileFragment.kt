package net.typeblog.socks

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.preference.CheckBoxPreference
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.text.InputType
import android.text.TextUtils
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Profile
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.Utility
import net.typeblog.socks.util.checkVpnStatusSuspend
import java.util.Locale

class ProfileFragment : PreferenceFragment(), OnPreferenceClickListener, OnPreferenceChangeListener,
    CompoundButton.OnCheckedChangeListener {
    private var mManager: ProfileManager? = null
    private var mProfile: Profile? = null

    private var mSwitch: Switch? = null
    private var mRunning = false
    private var mStarting = false
    private var mStopping = false
    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(p1: ComponentName?, binder: IBinder?) {
            mBinder = IVpnService.Stub.asInterface(binder)

            try {
                mRunning = mBinder!!.isRunning()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (mRunning) {
                updateState()
            }
        }

        override fun onServiceDisconnected(p1: ComponentName?) {
            mBinder = null
        }
    }
    private val mStateRunnable: Runnable = object : Runnable {
        override fun run() {
            updateState()
            mSwitch!!.postDelayed(this, 1000)
        }
    }
    private var mBinder: IVpnService? = null

    private var mPrefProfile: ListPreference? = null
    private var mPrefRoutes: ListPreference? = null
    private var mPrefServer: EditTextPreference? = null
    private var mPrefPort: EditTextPreference? = null
    private var mPrefUsername: EditTextPreference? = null
    private var mPrefPassword: EditTextPreference? = null
    private var mPrefDns: EditTextPreference? = null
    private var mPrefDnsPort: EditTextPreference? = null
    private var mPrefAppList: EditTextPreference? = null
    private var mPrefUDPGW: EditTextPreference? = null
    private var mPrefUserpw: CheckBoxPreference? = null
    private var mPrefPerApp: CheckBoxPreference? = null
    private var mPrefAppBypass: CheckBoxPreference? = null
    private var mPrefIPv6: CheckBoxPreference? = null
    private var mPrefUDP: CheckBoxPreference? = null
    private var mPrefAuto: CheckBoxPreference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.settings)
        setHasOptionsMenu(true)
        mManager = ProfileManager(getActivity().getApplicationContext())
        initPreferences()
        reload()
    }

    override fun onResume() {
        super.onResume()
        // Проверяем статус VPN при возврате в приложение
        // Это обновит UI (switch), если VPN был запущен через broadcast
        if (mSwitch != null) {
            checkState()
            GlobalScope.launch {
                val isRunning = context.checkVpnStatusSuspend()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Отвязываемся от сервиса при выходе из приложения
        if (mBinder != null) {
            try {
                getActivity().unbindService(mConnection)
            } catch (e: Exception) {
                // Игнорируем, если уже отвязаны
            }
            mBinder = null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.main, menu)

        val s = menu.findItem(R.id.switch_main)
        mSwitch = s.getActionView()!!.findViewById<Switch?>(R.id.switch_action_button)
        mSwitch!!.setOnCheckedChangeListener(this)
        mSwitch!!.postDelayed(mStateRunnable, 1000)
        checkState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.getItemId()
        if (id == R.id.prof_add) {
            addProfile()
            return true
        } else if (id == R.id.prof_del) {
            removeProfile()
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    override fun onPreferenceClick(p: Preference?): Boolean {
        // TODO: Implement this method
        return false
    }

    override fun onPreferenceChange(p: Preference?, newValue: Any): Boolean {
        if (p === mPrefProfile) {
            val name = newValue.toString()
            mProfile = mManager!!.getProfile(name)
            mManager!!.switchDefault(name)
            reload()
            return true
        } else if (p === mPrefServer) {
            mProfile!!.setServer(newValue.toString())
            resetTextN(mPrefServer!!, newValue)
            return true
        } else if (p === mPrefPort) {
            if (TextUtils.isEmpty(newValue.toString())) return false

            mProfile!!.setPort(newValue.toString().toInt())
            resetTextN(mPrefPort!!, newValue)
            return true
        } else if (p === mPrefUserpw) {
            mProfile!!.setIsUserpw(newValue.toString().toBoolean())
            return true
        } else if (p === mPrefUsername) {
            mProfile!!.setUsername(newValue.toString())
            resetTextN(mPrefUsername!!, newValue)
            return true
        } else if (p === mPrefPassword) {
            mProfile!!.setPassword(newValue.toString())
            resetTextN(mPrefPassword!!, newValue)
            return true
        } else if (p === mPrefRoutes) {
            mProfile!!.setRoute(newValue.toString())
            resetListN(mPrefRoutes!!, newValue)
            return true
        } else if (p === mPrefDns) {
            mProfile!!.setDns(newValue.toString())
            resetTextN(mPrefDns!!, newValue)
            return true
        } else if (p === mPrefDnsPort) {
            if (TextUtils.isEmpty(newValue.toString())) return false

            mProfile!!.setDnsPort(newValue.toString().toInt())
            resetTextN(mPrefDnsPort!!, newValue)
            return true
        } else if (p === mPrefPerApp) {
            mProfile!!.setIsPerApp(newValue.toString().toBoolean())
            return true
        } else if (p === mPrefAppBypass) {
            mProfile!!.setIsBypassApp(newValue.toString().toBoolean())
            return true
        } else if (p === mPrefAppList) {
            mProfile!!.setAppList(newValue.toString())
            return true
        } else if (p === mPrefIPv6) {
            mProfile!!.setHasIPv6(newValue.toString().toBoolean())
            return true
        } else if (p === mPrefUDP) {
            mProfile!!.setHasUDP(newValue.toString().toBoolean())
            return true
        } else if (p === mPrefUDPGW) {
            mProfile!!.setUDPGW(newValue.toString())
            resetTextN(mPrefUDPGW!!, newValue)
            return true
        } else if (p === mPrefAuto) {
            mProfile!!.setAutoConnect(newValue.toString().toBoolean())
            return true
        } else {
            return false
        }
    }

    override fun onCheckedChanged(p1: CompoundButton?, checked: Boolean) {
        if (checked) {
            startVpn()
        } else {
            stopVpn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            Utility.startVpn(getActivity(), mProfile)
            checkState()
        }
    }

    private fun initPreferences() {
        mPrefProfile = findPreference(Constants.PREF_PROFILE) as ListPreference
        mPrefServer = findPreference(Constants.PREF_SERVER_IP) as EditTextPreference
        mPrefPort = findPreference(Constants.PREF_SERVER_PORT) as EditTextPreference
        mPrefUserpw = findPreference(Constants.PREF_AUTH_USERPW) as CheckBoxPreference
        mPrefUsername = findPreference(Constants.PREF_AUTH_USERNAME) as EditTextPreference
        mPrefPassword = findPreference(Constants.PREF_AUTH_PASSWORD) as EditTextPreference
        mPrefRoutes = findPreference(Constants.PREF_ADV_ROUTE) as ListPreference
        mPrefDns = findPreference(Constants.PREF_ADV_DNS) as EditTextPreference
        mPrefDnsPort = findPreference(Constants.PREF_ADV_DNS_PORT) as EditTextPreference
        mPrefPerApp = findPreference(Constants.PREF_ADV_PER_APP) as CheckBoxPreference
        mPrefAppBypass = findPreference(Constants.PREF_ADV_APP_BYPASS) as CheckBoxPreference
        mPrefAppList = findPreference(Constants.PREF_ADV_APP_LIST) as EditTextPreference
        mPrefIPv6 = findPreference(Constants.PREF_IPV6_PROXY) as CheckBoxPreference
        mPrefUDP = findPreference(Constants.PREF_UDP_PROXY) as CheckBoxPreference
        mPrefUDPGW = findPreference(Constants.PREF_UDP_GW) as EditTextPreference
        mPrefAuto = findPreference(Constants.PREF_ADV_AUTO_CONNECT) as CheckBoxPreference

        mPrefProfile!!.setOnPreferenceChangeListener(this)
        mPrefServer!!.setOnPreferenceChangeListener(this)
        mPrefPort!!.setOnPreferenceChangeListener(this)
        mPrefUserpw!!.setOnPreferenceChangeListener(this)
        mPrefUsername!!.setOnPreferenceChangeListener(this)
        mPrefPassword!!.setOnPreferenceChangeListener(this)
        mPrefRoutes!!.setOnPreferenceChangeListener(this)
        mPrefDns!!.setOnPreferenceChangeListener(this)
        mPrefDnsPort!!.setOnPreferenceChangeListener(this)
        mPrefPerApp!!.setOnPreferenceChangeListener(this)
        mPrefAppBypass!!.setOnPreferenceChangeListener(this)
        mPrefAppList!!.setOnPreferenceChangeListener(this)
        mPrefIPv6!!.setOnPreferenceChangeListener(this)
        mPrefUDP!!.setOnPreferenceChangeListener(this)
        mPrefUDPGW!!.setOnPreferenceChangeListener(this)
        mPrefAuto!!.setOnPreferenceChangeListener(this)
    }

    private fun reload() {
        if (mProfile == null) {
            mProfile = mManager!!.getDefault()
        }

        mPrefProfile!!.setEntries(mManager!!.getProfiles())
        mPrefProfile!!.setEntryValues(mManager!!.getProfiles())
        mPrefProfile!!.setValue(mProfile!!.getName())
        mPrefRoutes!!.setValue(mProfile!!.getRoute())
        resetList(mPrefProfile!!, mPrefRoutes!!)

        mPrefUserpw!!.setChecked(mProfile!!.isUserPw())
        mPrefPerApp!!.setChecked(mProfile!!.isPerApp())
        mPrefAppBypass!!.setChecked(mProfile!!.isBypassApp())
        mPrefIPv6!!.setChecked(mProfile!!.hasIPv6())
        mPrefUDP!!.setChecked(mProfile!!.hasUDP())
        mPrefAuto!!.setChecked(mProfile!!.autoConnect())

        mPrefServer!!.setText(mProfile!!.getServer())
        mPrefPort!!.setText(mProfile!!.getPort().toString())
        mPrefUsername!!.setText(mProfile!!.getUsername())
        mPrefPassword!!.setText(mProfile!!.getPassword())
        mPrefDns!!.setText(mProfile!!.getDns())
        mPrefDnsPort!!.setText(mProfile!!.getDnsPort().toString())
        mPrefUDPGW!!.setText(mProfile!!.getUDPGW())
        resetText(
            mPrefServer!!,
            mPrefPort!!,
            mPrefUsername!!,
            mPrefPassword!!,
            mPrefDns!!,
            mPrefDnsPort!!,
            mPrefUDPGW!!
        )

        mPrefAppList!!.setText(mProfile!!.getAppList())
    }

    private fun resetList(vararg pref: ListPreference) {
        for (p in pref) p.setSummary(p.getEntry())
    }

    private fun resetListN(pref: ListPreference, newValue: Any) {
        pref.setSummary(newValue.toString())
    }

    private fun resetText(vararg pref: EditTextPreference) {
        for (p in pref) {
            if ((p.getEditText()
                    .getInputType() and InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD
            ) {
                p.setSummary(p.getText())
            } else {
                if (p.getText().length > 0) p.setSummary(
                    String.format(
                        Locale.US,
                        String.format(Locale.US, "%%0%dd", p.getText().length), 0
                    ).replace("0", "*")
                )
                else p.setSummary("")
            }
        }
    }

    private fun resetTextN(pref: EditTextPreference, newValue: Any) {
        if ((pref.getEditText()
                .getInputType() and InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD
        ) {
            pref.setSummary(newValue.toString())
        } else {
            val text = newValue.toString()
            if (text.length > 0) pref.setSummary(
                String.format(
                    Locale.US,
                    String.format(Locale.US, "%%0%dd", text.length), 0
                ).replace("0", "*")
            )
            else pref.setSummary("")
        }
    }

    private fun addProfile() {
        val e = EditText(getActivity())
        e.setSingleLine(true)

        AlertDialog.Builder(getActivity())
            .setTitle(R.string.prof_add)
            .setView(e)
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    val name = e.getText().toString().trim { it <= ' ' }
                    if (!TextUtils.isEmpty(name)) {
                        val p = mManager!!.addProfile(name)

                        if (p != null) {
                            mProfile = p
                            reload()
                            return@OnClickListener
                        }
                    }
                    Toast.makeText(
                        getActivity(),
                        String.format(getString(R.string.err_add_prof), name),
                        Toast.LENGTH_SHORT
                    ).show()
                })
            .setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int -> })
            .create().show()
    }

    private fun removeProfile() {
        AlertDialog.Builder(getActivity())
            .setTitle(R.string.prof_del)
            .setMessage(String.format(getString(R.string.prof_del_confirm), mProfile!!.getName()))
            .setPositiveButton(
                android.R.string.ok,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int ->
                    if (!mManager!!.removeProfile(
                            mProfile!!.getName()
                        )
                    ) {
                        Toast.makeText(
                            getActivity(),
                            getString(R.string.err_del_prof, mProfile!!.getName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        mProfile = mManager!!.getDefault()
                        reload()
                    }
                })
            .setNegativeButton(
                android.R.string.cancel,
                DialogInterface.OnClickListener { d: DialogInterface?, which: Int -> })
            .create().show()
    }

    private fun checkState() {
        mRunning = false
        mSwitch!!.setEnabled(false)
        mSwitch!!.setOnCheckedChangeListener(null)

        if (mBinder == null) {
            getActivity().bindService(
                Intent(getActivity(), SocksVpnService::class.java),
                mConnection,
                0
            )
        }
    }

    private fun updateState() {
        if (mBinder == null) {
            mRunning = false
        } else {
            try {
                mRunning = mBinder!!.isRunning()
            } catch (e: Exception) {
                mRunning = false
            }
        }

        mSwitch!!.setChecked(mRunning)

        if ((!mStarting && !mStopping) || (mStarting && mRunning) || (mStopping && !mRunning)) {
            mSwitch!!.setEnabled(true)
        }

        if (mStarting && mRunning) {
            mStarting = false
        }

        if (mStopping && !mRunning) {
            mStopping = false
        }

        mSwitch!!.setOnCheckedChangeListener(this@ProfileFragment)
    }

    private fun startVpn() {
        mStarting = true
        val i = VpnService.prepare(getActivity())

        if (i != null) {
            startActivityForResult(i, 0)
        } else {
            onActivityResult(0, Activity.RESULT_OK, null)
        }
    }

    private fun stopVpn() {
        if (mBinder == null) return

        mStopping = true

        try {
            mBinder!!.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mBinder = null

        getActivity().unbindService(mConnection)
        checkState()
    }
}
