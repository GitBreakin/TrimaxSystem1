-- TEST ONLY. Run by sql/tests/run_tests.sh after 20261025_03 (and the Stage B tests) and _07.
-- SOS / problem reports end to end: a phone sends one, only the Desktop (owner) sees it, an active Admin
-- acknowledges and resolves it with a note, nobody else can, a retry never duplicates or reopens it.
\set ON_ERROR_STOP on
\set QUIET on
set client_min_messages = notice;

\set dee  '00000000-0000-0000-0000-00000000e0e0'

-- ------------------------------------------------------------------ fixtures and privileges (superuser)
insert into public.users (username, password, name, email, contact_num, role_id, account_status)
select 'old.admin', 'x', 'Old Admin', 'oldadmin@example.test', '09170000099', r.role_id, 'Disabled'
  from public.roles r where r.role_name = 'Admin'
on conflict do nothing;
insert into public.admin_notifications (kind, category, title, body)
values ('Announcement', 'General', 'Office closed on Monday', 'Holiday') returning notification_id as ann_id \gset

select test.ok(not has_function_privilege('authenticated', 'trimax_private.resolve_alert(text,bigint,text)', 'execute')
               and not has_function_privilege('anon', 'trimax_private.resolve_alert(text,bigint,text)', 'execute')
               and not has_function_privilege('service_role', 'trimax_private.resolve_alert(text,bigint,text)', 'execute'),
               'only the database owner (Desktop) can resolve alerts');
select test.ok(not has_table_privilege('authenticated', 'public.admin_notifications', 'select')
               and not has_table_privilege('authenticated', 'public.admin_notifications', 'update'),
               'phones can neither read nor change alerts');

-- ------------------------------------------------------------------ phone: driver.dee sends an SOS
set role authenticated;
select test.jwt(:'dee');
select (public.trimax_report_issue('SOS', 'Engine overheating near Bocaue toll', null, 14.8, 120.95,
        'eeeeeeee-0000-0000-0000-0000000000a1') ->> 'notification_id')::bigint as sos \gset
select (public.trimax_report_issue('PROBLEM', 'Truck tyre low, continuing slowly', null, null, null,
        'eeeeeeee-0000-0000-0000-0000000000a2') ->> 'notification_id')::bigint as prob \gset
select test.ok(:sos > 0 and :prob > 0, 'driver SOS and problem report saved');
select test.expect_error($$select * from public.admin_notifications$$, '42501', 'the driver cannot read alerts (or their resolution)');
select test.expect_error(format($$select trimax_private.resolve_alert('marc.admin', %s, 'done')$$, :sos), '42501',
                         'a phone cannot resolve an alert, even naming an admin');
reset role;

-- ------------------------------------------------------------------ Desktop (owner)
set role app_owner;
select test.ok((select category = 'SOS' and reported_by = 'driver.dee' and kind = 'Alert' and resolved_at is null
                  and read_at is null and created_at is not null
                from admin_notifications where notification_id = :sos),
               'the Desktop sees the SOS with category, reporter and time, open and unread');
select test.ok((select count(*) from admin_notifications where kind = 'Alert' and resolved_at is null
                  and notification_id in (:sos, :prob)) = 2, 'both reports are in the open-alerts list');

select test.expect_error(format($$select trimax_private.resolve_alert('driver.dan', %s, 'I fixed it')$$, :sos),
                         'TM403', 'a driver account cannot resolve');
select test.expect_error(format($$select trimax_private.resolve_alert('old.admin', %s, 'Handled')$$, :sos),
                         'TM403', 'a disabled admin cannot resolve');
select test.expect_error(format($$select trimax_private.resolve_alert('marc.admin', %s, '   ')$$, :sos),
                         'TM422', 'a note is required');
select test.expect_error(format($$select trimax_private.resolve_alert('marc.admin', %s, %L)$$, :sos, repeat('x', 1001)),
                         'TM422', 'a note over 1000 characters is refused');
select test.expect_error($$select trimax_private.resolve_alert('marc.admin', 999999999, 'Handled')$$,
                         'TM404', 'an alert that does not exist');
select test.expect_error(format($$select trimax_private.resolve_alert('marc.admin', %s, 'Read it')$$, :'ann_id'),
                         'TM422', 'announcements cannot be resolved');
select test.ok((select resolved_at is null from admin_notifications where notification_id = :sos),
               'refused attempts changed nothing');

-- acknowledge first (the existing "mark read"), then resolve: the acknowledgement is kept
update admin_notifications set read_at = now() - interval '5 minutes', read_by = 'agent.ben' where notification_id = :prob;
select trimax_private.resolve_alert('marc.admin', :sos, '  Called Dee; tow truck sent. Booking moved to Dan.  ') as res \gset
select test.ok((:'res'::jsonb ->> 'resolved_by') = 'marc.admin', 'active admin resolves the SOS');
select test.ok((select resolution_note = 'Called Dee; tow truck sent. Booking moved to Dan.' and resolved_by = 'marc.admin'
                  and read_by = 'marc.admin' and read_at = resolved_at
                from admin_notifications where notification_id = :sos),
               'note stored trimmed; an unread alert is acknowledged by the resolver at the same time');
select trimax_private.resolve_alert('marc.admin', :prob, 'Told driver to stop at the next shop.') \gset
select test.ok((select read_by = 'agent.ben' and read_at < resolved_at from admin_notifications where notification_id = :prob),
               'an earlier acknowledgement (who and when) is kept');

select test.expect_error(format($$select trimax_private.resolve_alert('marc.admin', %s, 'Again')$$, :sos),
                         'TM409', 'a second resolve is refused ("already resolved by ...")');
select test.ok((select resolution_note like 'Called Dee%' from admin_notifications where notification_id = :sos),
               'the first resolution is not overwritten');
select test.ok((select count(*) from public.audit_log where action = 'notification.resolve'
                  and target_id in (:'sos', :'prob')) = 2,
               'each resolution is in the audit log');
select test.ok(not exists (select 1 from public.audit_log where action = 'notification.resolve' and details like '%tow truck%'),
               'the audit log does not copy the free-text note');
select test.ok((select count(*) from admin_notifications where kind = 'Alert' and resolved_at is null
                  and notification_id in (:sos, :prob)) = 0, 'resolved alerts leave the open-alerts list');

-- the table itself refuses half-recorded or misplaced resolutions
select test.expect_error(format($$update admin_notifications set resolved_at = now() where notification_id = %s$$, :'ann_id'),
                         '23514', 'a resolution needs who, when and a note, and only on alerts');
select test.expect_error(format($$update admin_notifications set resolution_note = '' where notification_id = %s$$, :sos),
                         '23514', 'an empty note cannot be stored');
reset role;

-- ------------------------------------------------------------------ phone retry after resolution
set role authenticated;
select test.jwt(:'dee');
select test.ok((public.trimax_report_issue('SOS', 'Engine overheating near Bocaue toll', null, 14.8, 120.95,
        'eeeeeeee-0000-0000-0000-0000000000a1') ->> 'already_recorded')::boolean,
        'the phone re-sending the same report (outbox retry) gets the first alert back');
reset role;
select test.ok((select count(*) from admin_notifications where client_request_id = 'eeeeeeee-0000-0000-0000-0000000000a1') = 1
               and (select resolved_at is not null from admin_notifications where notification_id = :sos),
               'no duplicate alert, and the retry does not reopen it');

select 'ALL ALERT RESOLUTION SQL TESTS PASSED';
