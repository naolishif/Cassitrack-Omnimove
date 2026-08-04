// ── OmniMove i18n — shared by login + traveller ───────────────────
const OMNI_T = {
  en: {
    // Login page
    tab_signin:'Sign In', tab_register:'Register',
    label_name:'Name', label_surname:'Surname',
    label_password:'Password', label_confirm_pass:'Confirm Password',
    label_new_pass:'New Password', label_confirm_new_pass:'Confirm New Password',
    ph_email:'your@email.com', ph_origin:'Origin', ph_dest:'Destination',
    ph_min8:'Min 8 characters', ph_repeat:'Repeat your password', ph_repeat_new:'Repeat new password',
    btn_signin:'Sign In', btn_create:'Create Account',
    btn_forgot:'Forgot password?', btn_send_reset:'Send Reset Link',
    btn_back_signin:'← Back to Sign In', btn_set_pass:'Set New Password',
    btn_resend:'Resend verification email',
    note_forgot:"Enter your email and we'll send you a link to reset your password.",
    note_reset:'Choose a new password for your OMNIMOVE account.',
    email_sent_p:'Open the email and click the link to activate your account, then come back here to sign in.',
    lang_choose:'Language',
    // Sidebar
    brand_tagline:'Smarter urban mobility',
    nav_section_nav:'Navigation',
    nav_plan:'Plan Route', nav_tickets:'My Tickets',
    nav_section_account:'My Account',
    nav_history:'Last Routes', nav_favourites:'Favourite Routes',
    nav_payment:'Payment', nav_preferences:'Preferences', nav_account:'Account',
    eco_score:'Your Eco Score',
    // Topbar
    search_btn:'Search',
    time_depart:'Depart at', time_arrive:'Arrive by',
    time_now:'Now', btn_done:'Done',
    // Login messages & validation
    err_required:'Mandatory field',
    err_pwd_weak:'Min 8 chars: uppercase, lowercase, number & special character',
    err_pwd_match:'Passwords do not match',
    err_not_verified:'Email not yet verified. Please check your inbox.',
    msg_email_verified:'✓ Email verified! You can now sign in.',
    msg_link_expired:'Verification link expired. Please register again or request a new link.',
    msg_link_invalid:'Invalid verification link.',
    msg_welcome_back:'Welcome back, {name}! Redirecting…',
    msg_wrong_creds:'Incorrect email or password',
    msg_login_failed:'Login failed. Please try again.',
    msg_too_many:' — Too many failed attempts, use "Forgot password?" below.',
    msg_server_err:'Cannot reach OMNIMOVE server. Check it is running on :8180',
    msg_account_created:'Account created! Verification email sent.',
    msg_register_failed:'Registration failed',
    msg_no_email:'Cannot determine email address.',
    msg_reset_resent:'Reset link resent. Check your inbox (and spam folder).',
    msg_server_short:'Cannot reach server.',
    msg_check_email:'Check your email (and spam folder).',
    msg_pwd_updated:'Password updated! You can now sign in.',
    msg_reset_failed:'Reset failed. The link may have expired.',
    btn_sending:'Sending…',
    // Main sheet
    smart_routes:'Smart Routes',
    plan_trip_title:'Plan your trip',
    plan_trip_desc:'Pick origin & destination above, then tap Search.',
    // Sort chips
    chip_eco:'🌱 Eco', chip_budget:'💸 Budget', chip_fast:'⚡ Fast',
    // Mode chips / buttons
    chip_bus:'🚌 Bus', chip_bike:'🚲 Bike', chip_scooter:'🛴 Scooter',
    btn_bus:'Select Bus', btn_bike:'Select Bike',
    btn_scooter:'Select Scooter', btn_walk:'Start Walking',
    // Profile tabs
    ptab_history:'Last Routes', ptab_favourites:'Favourites',
    ptab_payment:'Payment', ptab_settings:'Preferences', ptab_account:'Account',
    // Profile stats
    stat_eco_pts:'Eco pts', stat_co2:'CO₂ saved', stat_trips:'Trips', stat_spent:'Spent (30d)',
    // Tickets
    section_my_tickets:'My Tickets',
    label_active_ticket:'Active Ticket',
    btn_show_qr:'📱 Show QR',
    label_buy_ticket:'Buy New Ticket',
    type_bus:'Bus', type_scooter:'Scooter', type_bike:'Bike',
    ticket_single:'Single Ride', ticket_single_desc:'90 min · All urban lines',
    ticket_daily:'Daily Pass', ticket_daily_desc:'24 h · Unlimited rides',
    ticket_weekly:'Weekly Pass', ticket_weekly_desc:'7 days · Unlimited rides',
    ticket_monthly:'Monthly Pass', ticket_monthly_desc:'30 days · All urban lines',
    btn_buy:'Buy',
    ticket_unlock:'Standard Unlock', ticket_unlock_desc:'€1.00 unlock + €0.15/min',
    ticket_30min:'30 Min Bundle', ticket_30min_desc:'No unlock fee included',
    ticket_60min:'60 Min Bundle', ticket_60min_desc:'Best value for longer rides',
    price_paygo:'Pay-as-go', btn_start:'Start',
    ticket_casual:'Casual Ride', ticket_casual_desc:'Up to 15 minutes',
    ticket_hour:'Hour Pass', ticket_hour_desc:'Up to 60 minutes',
    ticket_day:'Day Pass', ticket_day_desc:'Unlimited rides, 24 h',
    btn_unlock_bike:'Unlock',
    // Profile section labels
    label_recent_trips:'Recent Trips',
    label_saved_routes:'Saved Favourite Routes',
    label_payment_methods:'Saved Payment Methods',
    pay_default:'Default',
    btn_add_payment:'+ Add Payment Method',
    // Settings – Journey Preferences
    label_journey_prefs:'Journey Preferences',
    label_default_mode:'Default journey mode',
    opt_eco:'🌱 Eco', opt_budget:'💸 Budget', opt_fast:'⚡ Fast',
    pref_avoid_occupancy:'Avoid high-occupancy buses',
    desc_avoid_occupancy:'Only show routes with low to medium occupancy',
    pref_show_walking:'Show walking options',
    desc_show_walking:'Include walking as a route alternative',
    pref_ebike:'E-bike preferred over bus',
    desc_ebike:'Prioritise bike sharing when available',
    pref_rain:'Hide bike/scooter/walk when raining',
    desc_rain:'Only show bus options when the weather is rainy',
    // Settings – Notifications
    label_notifications:'Notifications',
    pref_delay_alerts:'Route delay alerts',
    desc_delay_alerts:'Get notified when your route has a delay',
    pref_ticket_reminder:'Ticket expiry reminders',
    desc_ticket_reminder:'Alert 10 min before ticket expires',
    pref_eco_tip:'Eco tip of the day',
    desc_eco_tip:'Daily sustainability tips for your commute',
    // Settings – Save
    label_save_changes:'Save Changes',
    btn_save_prefs:'Save Preferences',
    // Settings
    section_profile:'Profile', section_language:'Language',
    lang_label:'App language',
    lang_desc:'Choose your preferred language for the OmniMove interface',
    section_session:'Session',
    logout_label:'Log out of OmniMove',
    logout_desc:'You will need to sign in again to access your account',
    logout_btn:'Log Out',
    section_actions:'Account Actions',
    delete_label:'Delete account', delete_desc:'Permanently removes your account and all data.',
    delete_btn:'Delete',
    // Delete modal
    modal_delete_title:'Delete Account',
    modal_delete_body:'Are you sure? This will permanently delete your account and all associated data. This action cannot be undone.',
    btn_cancel:'Cancel',
    btn_confirm_delete:'Yes, delete my account',
    // AI overlay
    ai_title:'OmniAI Assistant',
    ai_subtitle:'Ask anything about your route or mobility options',
    ph_ai_input:'Ask me anything...',
    btn_send_ai:'Send ↗',
    // Journey dynamic
    journey_in_progress:'Journey In Progress',
    min_left:'min left', end_journey:'End Journey',
    your_destination:'🏁 Your destination',
    live_tracking:'Live tracking active',
    walk:'Walk', bike:'Bike', scooter:'Scooter', wait_lbl:'Wait',
    stops_hide:'▴ Hide stops',
    next_bus:'next bus',
    min_wait:'min wait',
    on_time:'On time', delay_unknown:'Delay unknown', next_stop:'Next stop',
    no_routes:'No routes found.',
    no_trips:'No trips yet. Start your first journey!',
    no_favs:'No favourites yet. Tap the ☆ on a trip to save its route here.',
    lbl_today:'Today', lbl_yesterday:'Yesterday',
    // Stop arrivals sheet
    btn_check_buses:'Check next buses',
    loading_buses:'Loading next buses…',
    lbl_line:'line', lbl_lines:'lines',
    lbl_next_departures:'next departures',
    no_buses:'No upcoming buses found',
    err_arrivals:'Could not load arrivals',
    err_service:'Service unavailable',
    lbl_real_time:'Real time', lbl_scheduled:'Scheduled',
    lbl_now:'Now',
    lbl_crowding:'Crowding:',
    crowd_low:'Low', crowd_medium:'Medium', crowd_high:'High', crowd_very_high:'Very High',
    // Route cards
    // Mode labels on route cards (opt.mode → display name)
    mode_bus:'Bus', mode_bike:'Bike', mode_scooter:'Scooter', mode_walk:'Walking',
    metric_cost:'Cost', metric_green:'Green',
    badge_available:'✓ Available',
    btn_start_journey:'Start Journey',
    lbl_stops:'stops', lbl_stop:'stop',
    no_stops_avail:'No stops available',
    // Search flow
    finding_routes:'Finding best routes...',
    err_rate_limited:'Too many searches. Please wait a moment before trying again.',
    err_load_routes:'Could not load routes.',
    lbl_free:'Free',
    // Journey end
    journey_completed:'Journey completed!',
    search_new_route:'Search a new route above',
    // Weather conditions (condition code → full pill text, {t} = temperature placeholder)
    weather_CLEAR:'☀️ Great weather in Cassino ({t}) — all transport options available!',
    weather_CLOUDY:'☁️ Overcast but dry ({t}) — all transport options available.',
    weather_RAIN:'🌧️ Rain in Cassino ({t}) — bus is the best choice today.',
    weather_HEAVY_RAIN:'⛈️ Heavy rain ({t}) — take the bus or walk with an umbrella.',
    weather_STORM:'⛈️ Storm warning ({t}) — bus only. Avoid outdoor modes.',
    weather_SNOW:'❄️ Snow in Cassino ({t}) — bus only. Roads may be slippery.',
    weather_HOT:'🌡️ Very hot today ({t}) — consider travelling early morning.',
    weather_WINDY:'💨 Strong wind ({t}) — scooter not recommended today.',
  },
  it: {
    // Login page
    tab_signin:'Accedi', tab_register:'Registrati',
    label_name:'Nome', label_surname:'Cognome',
    label_password:'Password', label_confirm_pass:'Conferma password',
    label_new_pass:'Nuova password', label_confirm_new_pass:'Conferma nuova password',
    ph_email:'tua@email.com', ph_origin:'Origine', ph_dest:'Destinazione',
    ph_min8:'Min 8 caratteri', ph_repeat:'Ripeti la password', ph_repeat_new:'Ripeti la nuova password',
    btn_signin:'Accedi', btn_create:'Crea account',
    btn_forgot:'Password dimenticata?', btn_send_reset:'Invia link di reset',
    btn_back_signin:'← Torna al login', btn_set_pass:'Imposta password',
    btn_resend:'Reinvia email di verifica',
    note_forgot:'Inserisci la tua email e ti invieremo un link per reimpostare la password.',
    note_reset:'Scegli una nuova password per il tuo account OMNIMOVE.',
    email_sent_p:"Apri l'email e clicca sul link per attivare il tuo account, poi torna qui per accedere.",
    lang_choose:'Lingua',
    // Sidebar
    brand_tagline:'Mobilità urbana intelligente',
    nav_section_nav:'Navigazione',
    nav_plan:'Pianifica percorso', nav_tickets:'I miei biglietti',
    nav_section_account:'Il mio account',
    nav_history:'Ultimi percorsi', nav_favourites:'Percorsi preferiti',
    nav_payment:'Pagamento', nav_preferences:'Preferenze', nav_account:'Account',
    eco_score:'Il tuo Eco Score',
    // Topbar
    search_btn:'Cerca',
    time_depart:'Parte alle', time_arrive:'Arriva entro',
    time_now:'Adesso', btn_done:'Fatto',
    // Login messages & validation
    err_required:'Campo obbligatorio',
    err_pwd_weak:'Min 8 caratteri: maiuscola, minuscola, numero e carattere speciale',
    err_pwd_match:'Le password non coincidono',
    err_not_verified:'Email non ancora verificata. Controlla la tua casella di posta.',
    msg_email_verified:'✓ Email verificata! Puoi ora accedere.',
    msg_link_expired:'Link di verifica scaduto. Registrati di nuovo o richiedi un nuovo link.',
    msg_link_invalid:'Link di verifica non valido.',
    msg_welcome_back:'Bentornato, {name}! Reindirizzamento…',
    msg_wrong_creds:'Email o password errata',
    msg_login_failed:'Accesso fallito. Riprova.',
    msg_too_many:' — Troppi tentativi falliti, usa "Password dimenticata?" qui sotto.',
    msg_server_err:'Impossibile raggiungere il server OMNIMOVE. Verifica che sia in esecuzione su :8180',
    msg_account_created:'Account creato! Email di verifica inviata.',
    msg_register_failed:'Registrazione fallita',
    msg_no_email:"Impossibile determinare l'indirizzo email.",
    msg_reset_resent:'Link di reset reinviato. Controlla la posta in arrivo (e spam).',
    msg_server_short:'Impossibile raggiungere il server.',
    msg_check_email:'Controlla la tua email (e la cartella spam).',
    msg_pwd_updated:'Password aggiornata! Puoi ora accedere.',
    msg_reset_failed:'Reset fallito. Il link potrebbe essere scaduto.',
    btn_sending:'Invio…',
    // Main sheet
    smart_routes:'Percorsi intelligenti',
    plan_trip_title:'Pianifica il tuo percorso',
    plan_trip_desc:'Scegli origine e destinazione qui sopra, poi premi Cerca.',
    // Sort chips
    chip_eco:'🌱 Eco', chip_budget:'💸 Economico', chip_fast:'⚡ Veloce',
    // Mode chips / buttons
    chip_bus:'🚌 Bus', chip_bike:'🚲 Bici', chip_scooter:'🛴 Monopattino',
    btn_bus:'Scegli Bus', btn_bike:'Scegli Bici',
    btn_scooter:'Scegli Monopattino', btn_walk:'Inizia a camminare',
    // Profile tabs
    ptab_history:'Ultimi percorsi', ptab_favourites:'Preferiti',
    ptab_payment:'Pagamento', ptab_settings:'Preferenze', ptab_account:'Account',
    // Profile stats
    stat_eco_pts:'Eco pts', stat_co2:'CO₂ risparmiata', stat_trips:'Percorsi', stat_spent:'Spesi (30g)',
    // Tickets
    section_my_tickets:'I miei biglietti',
    label_active_ticket:'Biglietto attivo',
    btn_show_qr:'📱 Mostra QR',
    label_buy_ticket:'Acquista biglietto',
    type_bus:'Bus', type_scooter:'Monopattino', type_bike:'Bici',
    ticket_single:'Corsa singola', ticket_single_desc:'90 min · Tutte le linee urbane',
    ticket_daily:'Abbonamento giornaliero', ticket_daily_desc:'24 h · Corse illimitate',
    ticket_weekly:'Abbonamento settimanale', ticket_weekly_desc:'7 giorni · Corse illimitate',
    ticket_monthly:'Abbonamento mensile', ticket_monthly_desc:'30 giorni · Tutte le linee urbane',
    btn_buy:'Acquista',
    ticket_unlock:'Sblocco standard', ticket_unlock_desc:'€1.00 sblocco + €0.15/min',
    ticket_30min:'Pacchetto 30 min', ticket_30min_desc:'Sblocco incluso',
    ticket_60min:'Pacchetto 60 min', ticket_60min_desc:'Il migliore per percorsi più lunghi',
    price_paygo:'A consumo', btn_start:'Inizia',
    ticket_casual:'Corsa occasionale', ticket_casual_desc:'Fino a 15 minuti',
    ticket_hour:'Pass orario', ticket_hour_desc:'Fino a 60 minuti',
    ticket_day:'Pass giornaliero', ticket_day_desc:'Corse illimitate, 24 h',
    btn_unlock_bike:'Sblocca',
    // Profile section labels
    label_recent_trips:'Percorsi recenti',
    label_saved_routes:'Percorsi preferiti salvati',
    label_payment_methods:'Metodi di pagamento salvati',
    pay_default:'Predefinito',
    btn_add_payment:'+ Aggiungi metodo di pagamento',
    // Settings – Journey Preferences
    label_journey_prefs:'Preferenze percorso',
    label_default_mode:'Modalità percorso predefinita',
    opt_eco:'🌱 Eco', opt_budget:'💸 Economico', opt_fast:'⚡ Veloce',
    pref_avoid_occupancy:'Evita bus ad alta occupazione',
    desc_avoid_occupancy:'Mostra solo percorsi con bassa o media occupazione',
    pref_show_walking:'Mostra opzioni a piedi',
    desc_show_walking:"Includi a piedi come alternativa al percorso",
    pref_ebike:'E-bike preferita al bus',
    desc_ebike:'Dai priorità al bike sharing quando disponibile',
    pref_rain:'Nascondi bici/monopattino/piedi con pioggia',
    desc_rain:'Mostra solo bus quando il tempo è piovoso',
    // Settings – Notifications
    label_notifications:'Notifiche',
    pref_delay_alerts:'Avvisi ritardo percorso',
    desc_delay_alerts:'Ricevi notifiche quando il tuo percorso ha un ritardo',
    pref_ticket_reminder:'Promemoria scadenza biglietto',
    desc_ticket_reminder:'Avviso 10 min prima della scadenza del biglietto',
    pref_eco_tip:'Consiglio eco del giorno',
    desc_eco_tip:'Suggerimenti giornalieri sulla sostenibilità per i tuoi spostamenti',
    // Settings – Save
    label_save_changes:'Salva modifiche',
    btn_save_prefs:'Salva preferenze',
    // Settings
    section_profile:'Profilo', section_language:'Lingua',
    lang_label:"Lingua dell'app",
    lang_desc:"Scegli la lingua preferita per l'interfaccia OmniMove",
    section_session:'Sessione',
    logout_label:'Esci da OmniMove',
    logout_desc:'Dovrai accedere di nuovo per usare il tuo account',
    logout_btn:'Esci',
    section_actions:'Azioni account',
    delete_label:'Elimina account', delete_desc:'Rimuove definitivamente il tuo account e tutti i dati.',
    delete_btn:'Elimina',
    // Delete modal
    modal_delete_title:'Elimina account',
    modal_delete_body:'Sei sicuro? Questo eliminerà definitivamente il tuo account e tutti i dati associati. Questa azione non può essere annullata.',
    btn_cancel:'Annulla',
    btn_confirm_delete:'Sì, elimina il mio account',
    // AI overlay
    ai_title:'Assistente OmniAI',
    ai_subtitle:'Chiedi qualsiasi cosa sul tuo percorso o sulla mobilità',
    ph_ai_input:'Chiedi qualcosa...',
    btn_send_ai:'Invia ↗',
    // Journey dynamic
    journey_in_progress:'Percorso in corso',
    min_left:'min rimanenti', end_journey:'Termina percorso',
    your_destination:'🏁 La tua destinazione',
    live_tracking:'Tracking in tempo reale',
    walk:'A piedi', bike:'Bici', scooter:'Monopattino', wait_lbl:'Attesa',
    stops_hide:'▴ Nascondi fermate',
    next_bus:'prossimo bus',
    min_wait:'min attesa',
    on_time:'In orario', delay_unknown:'Ritardo sconosciuto', next_stop:'Prossima fermata',
    no_routes:'Nessun percorso trovato.',
    no_trips:'Nessun percorso ancora. Inizia il tuo primo viaggio!',
    no_favs:'Nessun preferito ancora. Tocca ☆ su un percorso per salvarlo qui.',
    lbl_today:'Oggi', lbl_yesterday:'Ieri',
    // Stop arrivals sheet
    btn_check_buses:'Prossimi bus',
    loading_buses:'Caricamento bus…',
    lbl_line:'linea', lbl_lines:'linee',
    lbl_next_departures:'prossime partenze',
    no_buses:'Nessun bus in arrivo',
    err_arrivals:'Impossibile caricare gli arrivi',
    err_service:'Servizio non disponibile',
    lbl_real_time:'Tempo reale', lbl_scheduled:'Programmato',
    lbl_now:'Ora',
    lbl_crowding:'Affollamento:',
    crowd_low:'Basso', crowd_medium:'Medio', crowd_high:'Alto', crowd_very_high:'Molto alto',
    // Route cards
    // Mode labels
    mode_bus:'Bus', mode_bike:'Bici', mode_scooter:'Monopattino', mode_walk:'A piedi',
    metric_cost:'Costo', metric_green:'Eco',
    badge_available:'✓ Disponibile',
    btn_start_journey:'Inizia percorso',
    lbl_stops:'fermate', lbl_stop:'fermata',
    no_stops_avail:'Nessuna fermata disponibile',
    // Search flow
    finding_routes:'Ricerca dei percorsi migliori...',
    err_rate_limited:'Troppe ricerche. Aspetta un momento prima di riprovare.',
    err_load_routes:'Impossibile caricare i percorsi.',
    lbl_free:'Gratuito',
    // Journey end
    journey_completed:'Percorso completato!',
    search_new_route:'Cerca un nuovo percorso qui sopra',
    // Weather conditions
    weather_CLEAR:'☀️ Bel tempo a Cassino ({t}) — tutte le opzioni di trasporto disponibili!',
    weather_CLOUDY:'☁️ Nuvoloso ma asciutto ({t}) — tutte le opzioni disponibili.',
    weather_RAIN:'🌧️ Pioggia a Cassino ({t}) — il bus è la scelta migliore oggi.',
    weather_HEAVY_RAIN:'⛈️ Pioggia intensa ({t}) — prendi il bus o cammina con un ombrello.',
    weather_STORM:'⛈️ Allerta temporale ({t}) — solo bus. Evita modalità all\'aperto.',
    weather_SNOW:'❄️ Neve a Cassino ({t}) — solo bus. Strade potenzialmente scivolose.',
    weather_HOT:'🌡️ Molto caldo oggi ({t}) — considera di viaggiare di prima mattina.',
    weather_WINDY:'💨 Vento forte ({t}) — monopattino sconsigliato oggi.',
  }
};

function getLang() {
  return localStorage.getItem('omnimove_lang')
      || (navigator.language?.toLowerCase().startsWith('it') ? 'it' : 'en');
}

function t(key) {
  const lang = getLang();
  return (OMNI_T[lang] && OMNI_T[lang][key] !== undefined ? OMNI_T[lang][key] : null)
      ?? (OMNI_T['en'][key] ?? key);
}

function applyTranslations() {
  const lang = getLang();
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.dataset.i18n;
    if (el.tagName === 'INPUT') { el.placeholder = t(key); }
    else { el.textContent = t(key); }
  });
  document.documentElement.lang = lang;
  // Sync all lang-pill toggles on this page
  document.querySelectorAll('.lang-pill').forEach(btn => {
    btn.classList.toggle('lang-pill-active', btn.dataset.lang === lang);
  });
}

function setLanguage(lang) {
  localStorage.setItem('omnimove_lang', lang);
  applyTranslations();
  // Allow pages to re-render dynamic content after language change
  if (typeof window._onLangChange === 'function') window._onLangChange(lang);
}
