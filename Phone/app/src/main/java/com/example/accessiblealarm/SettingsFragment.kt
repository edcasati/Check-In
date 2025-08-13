package com.example.accessiblealarm

import android.media.AudioManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import java.util.regex.Pattern
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import org.json.JSONArray
import org.json.JSONObject
import android.widget.ImageView

class SettingsFragment : Fragment() {
    private lateinit var audioManager: AppAudioManager
    private var selectedContacts = mutableListOf<Contact>()
    private lateinit var contactsAdapter: SelectedContactsAdapter
    private lateinit var selectedContactsRecycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = AppAudioManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val usernameInput = view.findViewById<TextInputEditText>(R.id.username_input)
        val alarmVolumeSlider = view.findViewById<SeekBar>(R.id.alarm_volume_slider)
        val contactsButton = view.findViewById<Button>(R.id.contacts_button)
        selectedContactsRecycler = view.findViewById(R.id.selected_contacts_recycler)

        // Load saved contact numbers and checkbox states
        val sharedPreferences = requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
        val savedUsername = sharedPreferences.getString("username", "")
        usernameInput.setText(savedUsername)

        // Setup RecyclerView for selected contacts
        setupSelectedContactsRecycler()
        loadSelectedContacts()

        // Setup contacts button - now for adding contacts
        contactsButton.setOnClickListener {
            if (hasContactsPermission()) {
                showContactSelectionDialog()
            } else {
                requestContactsPermission()
            }
        }

        // Setup username persistence
        usernameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val username = usernameInput.text.toString()
                requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("username", username)
                    .apply()
                Toast.makeText(requireContext(), "User name saved", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup volume sliders
        alarmVolumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    sharedPreferences.edit().putInt("alarm_volume", progress).apply()
                    audioManager.setAppVolume(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                Toast.makeText(requireContext(), "Alarm volume saved", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        // Save any pending changes
        view?.clearFocus()
    }

    override fun onStop() {
        super.onStop()
        // Restore audio settings when the fragment is no longer visible
        audioManager.restoreOriginalVolume()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the audio restoration from onDestroy since it's now handled in onStop
    }

    private fun setupPhoneNumberFormatting(input: TextInputEditText, prefKey: String) {
        input.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting) return
                isFormatting = true

                val formatted = formatPhoneNumber(s.toString())
                if (formatted != s.toString()) {
                    input.setText(formatted)
                    input.setSelection(formatted.length)
                }

                isFormatting = false
            }
        })

        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val contactNumber = input.text.toString()
                if (isValidPhoneNumber(contactNumber)) {
                    requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString(prefKey, contactNumber)
                        .apply()
                    Toast.makeText(requireContext(), "Contact number saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatPhoneNumber(number: String): String {
        // Remove all non-digit characters
        val digits = number.replace(Regex("[^0-9]"), "")
        
        return when {
            digits.length <= 3 -> digits
            digits.length <= 6 -> "(${digits.substring(0, 3)})${digits.substring(3)}"
            else -> "(${digits.substring(0, 3)})${digits.substring(3, 6)}-${digits.substring(6, minOf(10, digits.length))}"
        }
    }

    private fun isValidPhoneNumber(number: String): Boolean {
        val digits = number.replace(Regex("[^0-9]"), "")
        return digits.length == 10
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.READ_CONTACTS),
            CONTACTS_PERMISSION_REQUEST_CODE
        )
    }



    private fun getContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val contentResolver = requireContext().contentResolver

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: "Unknown"
                val phoneNumber = it.getString(numberIndex) ?: ""
                if (phoneNumber.isNotEmpty()) {
                    contacts.add(Contact(name, phoneNumber))
                }
            }
        }

        return contacts
    }

    private fun setupSelectedContactsRecycler() {
        selectedContactsRecycler.layoutManager = LinearLayoutManager(requireContext())
        contactsAdapter = SelectedContactsAdapter(selectedContacts)
        selectedContactsRecycler.adapter = contactsAdapter
        
        // Show/hide hint based on whether contacts exist
        updateContactsVisibility()
    }

    private fun updateContactsVisibility() {
        val contactsHint = view?.findViewById<TextView>(R.id.contacts_hint)
        if (selectedContacts.isEmpty()) {
            contactsHint?.text = getString(R.string.no_contacts_selected)
        } else {
            contactsHint?.text = getString(R.string.tap_contact_for_details)
        }
    }

    private fun loadSelectedContacts() {
        val sharedPreferences = requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
        val savedContactsJson = sharedPreferences.getString("selected_contacts", "[]")
        val jsonArray = JSONArray(savedContactsJson)
        selectedContacts.clear()
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val name = jsonObject.getString("name")
            val phoneNumber = jsonObject.getString("phoneNumber")
            selectedContacts.add(Contact(name, phoneNumber))
        }
        contactsAdapter.notifyDataSetChanged()
        updateContactsVisibility()
    }

    private fun showContactSelectionDialog() {
        val contacts = getContacts()
        if (contacts.isEmpty()) {
            Toast.makeText(requireContext(), "No contacts found", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_contact_selection, null)
        val contactsRecycler = dialogView.findViewById<RecyclerView>(R.id.contacts_recycler)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancel_button)

                 val dialog = AlertDialog.Builder(requireContext())
             .setView(dialogView)
             .create()

         contactsRecycler.layoutManager = LinearLayoutManager(requireContext())
         val selectionAdapter = ContactSelectionAdapter(contacts) { selectedContact ->
             if (!selectedContacts.contains(selectedContact)) {
                 selectedContacts.add(selectedContact)
                 contactsAdapter.notifyDataSetChanged()
                 saveSelectedContacts()
                 updateContactsVisibility()
                 Toast.makeText(requireContext(), getString(R.string.contact_added), Toast.LENGTH_SHORT).show()
             } else {
                 Toast.makeText(requireContext(), getString(R.string.contact_already_exists), Toast.LENGTH_SHORT).show()
             }
             dialog.dismiss()
         }
         contactsRecycler.adapter = selectionAdapter

         cancelButton.setOnClickListener {
             dialog.dismiss()
         }

         dialog.show()
    }

    private fun saveSelectedContacts() {
        val sharedPreferences = requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (contact in selectedContacts) {
            val jsonObject = JSONObject()
            jsonObject.put("name", contact.name)
            jsonObject.put("phoneNumber", contact.phoneNumber)
            jsonArray.put(jsonObject)
        }
        sharedPreferences.edit().putString("selected_contacts", jsonArray.toString()).apply()
    }

         private fun showContactDetailsDialog(contact: Contact, position: Int) {
         val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_contact_details, null)
         
         // Initialize views
         val contactNameLarge = dialogView.findViewById<TextView>(R.id.contact_name_large)
         val contactPhoneLarge = dialogView.findViewById<TextView>(R.id.contact_phone_large)
         val contactInitialLarge = dialogView.findViewById<TextView>(R.id.contact_initial_large)
         val checkboxSuccess = dialogView.findViewById<CheckBox>(R.id.checkbox_success)
         val checkboxFail = dialogView.findViewById<CheckBox>(R.id.checkbox_fail)
         val checkboxEmergency = dialogView.findViewById<CheckBox>(R.id.checkbox_emergency)
         val removeButton = dialogView.findViewById<Button>(R.id.remove_contact_button)
         val closeButton = dialogView.findViewById<Button>(R.id.close_button)
         
         // Set contact information
         contactNameLarge.text = contact.name
         contactPhoneLarge.text = contact.phoneNumber
         val initial = if (contact.name.isNotEmpty()) {
             contact.name.first().uppercaseChar().toString()
         } else {
             "?"
         }
         contactInitialLarge.text = initial
         
         // Load saved checkbox states
         loadContactCheckboxStates(contact, checkboxSuccess, checkboxFail, checkboxEmergency)
         
         val dialog = AlertDialog.Builder(requireContext())
             .setView(dialogView)
             .create()
         
         // Set up button listeners
         removeButton.setOnClickListener {
             // Show confirmation dialog before removing contact
             AlertDialog.Builder(requireContext())
                 .setTitle(getString(R.string.remove_contact_confirmation_title))
                 .setMessage(getString(R.string.remove_contact_confirmation_message, contact.name))
                 .setPositiveButton(getString(R.string.remove)) { _, _ ->
                     selectedContacts.removeAt(position)
                     contactsAdapter.notifyItemRemoved(position)
                     saveSelectedContacts()
                     updateContactsVisibility()
                     Toast.makeText(requireContext(), "${contact.name} ${getString(R.string.contact_removed)}", Toast.LENGTH_SHORT).show()
                     dialog.dismiss()
                 }
                 .setNegativeButton(getString(R.string.cancel)) { confirmDialog, _ ->
                     confirmDialog.dismiss()
                 }
                 .show()
         }
         
         closeButton.setOnClickListener {
             // Save checkbox states before closing
             saveContactCheckboxStates(contact, checkboxSuccess, checkboxFail, checkboxEmergency)
             dialog.dismiss()
         }
         
         dialog.show()
     }

     private fun loadContactCheckboxStates(contact: Contact, successCheckbox: CheckBox, failCheckbox: CheckBox, emergencyCheckbox: CheckBox) {
         val sharedPreferences = requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
         val contactKey = getContactKey(contact)
         
         successCheckbox.isChecked = sharedPreferences.getBoolean("${contactKey}_success", false)
         failCheckbox.isChecked = sharedPreferences.getBoolean("${contactKey}_fail", false)
         emergencyCheckbox.isChecked = sharedPreferences.getBoolean("${contactKey}_emergency", false)
     }

     private fun saveContactCheckboxStates(contact: Contact, successCheckbox: CheckBox, failCheckbox: CheckBox, emergencyCheckbox: CheckBox) {
         val sharedPreferences = requireContext().getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
         val contactKey = getContactKey(contact)
         
         sharedPreferences.edit()
             .putBoolean("${contactKey}_success", successCheckbox.isChecked)
             .putBoolean("${contactKey}_fail", failCheckbox.isChecked)
             .putBoolean("${contactKey}_emergency", emergencyCheckbox.isChecked)
             .apply()
     }

     private fun getContactKey(contact: Contact): String {
         // Create a unique key based on contact name and phone number
         return "${contact.name}_${contact.phoneNumber}".replace("[^A-Za-z0-9_]".toRegex(), "_")
     }

    data class Contact(val name: String, val phoneNumber: String)

    inner class ContactSelectionAdapter(
        private val contacts: List<Contact>,
        private val onContactClick: (Contact) -> Unit
    ) : RecyclerView.Adapter<ContactSelectionAdapter.ContactViewHolder>() {

        inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val contactName: TextView = itemView.findViewById(R.id.contact_name)
            val contactPhone: TextView = itemView.findViewById(R.id.contact_phone)
            val contactInitial: TextView = itemView.findViewById(R.id.contact_initial)
            
                         init {
                 itemView.setOnClickListener {
                     val position = adapterPosition
                     if (position != RecyclerView.NO_POSITION) {
                         onContactClick(contacts[position])
                     }
                 }
             }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_selection, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]
            holder.contactName.text = contact.name
            holder.contactPhone.text = contact.phoneNumber
            
            // Set the first letter of the contact name as the initial
            val initial = if (contact.name.isNotEmpty()) {
                contact.name.first().uppercaseChar().toString()
            } else {
                "?"
            }
            holder.contactInitial.text = initial
        }

        override fun getItemCount(): Int = contacts.size
    }

    inner class SelectedContactsAdapter(private val contacts: MutableList<Contact>) : 
        RecyclerView.Adapter<SelectedContactsAdapter.ContactViewHolder>() {

        inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val contactName: TextView = itemView.findViewById(R.id.contact_name)
            val contactInitial: TextView = itemView.findViewById(R.id.contact_initial)
            
            init {
                itemView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        showContactDetailsDialog(contacts[position], position)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_contact, parent, false)
            return ContactViewHolder(view)
        }

        override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
            val contact = contacts[position]
            holder.contactName.text = contact.name
            
            // Set the first letter of the contact name as the initial
            val initial = if (contact.name.isNotEmpty()) {
                contact.name.first().uppercaseChar().toString()
            } else {
                "?"
            }
            holder.contactInitial.text = initial
        }

        override fun getItemCount(): Int = contacts.size
    }

    companion object {
        private const val CONTACTS_PERMISSION_REQUEST_CODE = 1001
        fun incrementMissedCount(context: android.content.Context) {
            val sharedPreferences = context.getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
            val currentCount = sharedPreferences.getInt("actual_missed_count", 0)
            sharedPreferences.edit().putInt("actual_missed_count", currentCount + 1).apply()
        }

        fun resetMissedCount(context: android.content.Context) {
            val sharedPreferences = context.getSharedPreferences("AlarmPrefs", android.content.Context.MODE_PRIVATE)
            sharedPreferences.edit().putInt("actual_missed_count", 0).apply()
        }
    }
} 