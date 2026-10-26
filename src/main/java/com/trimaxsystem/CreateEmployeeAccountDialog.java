package com.trimaxsystem;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.regex.Pattern;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;

final class CreateEmployeeAccountDialog extends JDialog {

    private static final Pattern PHONE = Pattern.compile("09[0-9]{9}");

    private final JTextField txtName = new JTextField(22);
    private final JTextField txtEmail = new JTextField(22);
    private final JTextField txtContact = new JTextField(22);
    private final JComboBox<String> cmbRole = new JComboBox<>(new String[]{"Agent", "Driver"});
    private final javax.swing.JButton btnCreate = UiKit.primaryButton("Create employee account");
    private final Runnable onCreated;

    CreateEmployeeAccountDialog(java.awt.Window owner, Runnable onCreated) {
        super(owner, "Create Employee Account", Dialog.ModalityType.APPLICATION_MODAL);
        this.onCreated = onCreated;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(new EmptyBorder(18, 20, 18, 20));
        setContentPane(root);

        JPanel heading = new JPanel(new BorderLayout(0, 4));
        heading.setOpaque(false);
        JLabel title = new JLabel("Create employee account");
        title.setFont(Theme.TITLE);
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel("Add an Agent or Driver account to TRI-MAX.");
        subtitle.setFont(Theme.SUBTLE);
        subtitle.setForeground(Theme.MUTED);
        heading.add(title, BorderLayout.NORTH);
        heading.add(subtitle, BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);

        styleField(txtName);
        styleField(txtEmail);
        styleField(txtContact);
        ((AbstractDocument) txtContact.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
                    throws BadLocationException {
                if (text != null && text.matches("[0-9]+")
                        && fb.getDocument().getLength() + text.length() <= 11) {
                    super.insertString(fb, offset, text, attrs);
                }
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                    throws BadLocationException {
                if (text != null && !text.isEmpty() && !text.matches("[0-9]+")) return;
                if (fb.getDocument().getLength() - length + (text == null ? 0 : text.length()) <= 11) {
                    super.replace(fb, offset, length, text, attrs);
                }
            }
        });
        cmbRole.setFont(Theme.SUBTLE);
        cmbRole.setBackground(Theme.CARD);
        cmbRole.setForeground(Theme.TEXT);

        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 10));
        fields.setOpaque(false);
        addField(fields, "Full name", txtName);
        addField(fields, "Email", txtEmail);
        addField(fields, "Contact number", txtContact);
        addField(fields, "Employee role", cmbRole);

        JPanel formCard = new JPanel(new BorderLayout());
        formCard.setBackground(Theme.CARD);
        formCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(16, 16, 16, 16)));
        formCard.add(fields, BorderLayout.CENTER);
        root.add(formCard, BorderLayout.CENTER);

        javax.swing.JButton btnCancel = UiKit.secondaryButton("Cancel");
        btnCancel.addActionListener(e -> dispose());
        btnCreate.addActionListener(e -> createAccount());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnCancel);
        actions.add(btnCreate);
        root.add(actions, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(btnCreate);
        pack();
        setMinimumSize(new Dimension(560, getHeight()));
        setLocationRelativeTo(owner);
    }

    private void styleField(JTextField field) {
        field.setFont(Theme.SUBTLE);
        field.setBackground(Theme.CARD);
        UiKit.styleField(field);
    }

    private void addField(JPanel fields, String caption, java.awt.Component field) {
        JPanel group = new JPanel(new BorderLayout(0, 5));
        group.setOpaque(false);
        group.add(UiKit.caption(caption), BorderLayout.NORTH);
        group.add(field, BorderLayout.CENTER);
        fields.add(group);
    }

    private void createAccount() {
        String contact = txtContact.getText().trim();
        if (!PHONE.matcher(contact).matches()) {
            JOptionPane.showMessageDialog(this,
                    "Contact number must be 11 digits and start with 09 (for example, 09123456789).",
                    "Invalid Contact Number", JOptionPane.WARNING_MESSAGE);
            txtContact.requestFocusInWindow();
            return;
        }

        String fullName = txtName.getText();
        String email = txtEmail.getText();
        String contactNumber = contact;
        String role = (String) cmbRole.getSelectedItem();
        setBusy(true);
        new SwingWorker<EmployeeOnboarding.Outcome, Void>() {
            @Override
            protected EmployeeOnboarding.Outcome doInBackground() throws Exception {
                // The account, then its phone sign-in with a temporary password, then the email.
                return EmployeeOnboarding.createEmployee(fullName, email, contactNumber, role);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    EmployeeOnboarding.Outcome outcome = get();
                    EmployeeOnboarding.show(CreateEmployeeAccountDialog.this, outcome, true);
                    if (onCreated != null) onCreated.run();
                    dispose();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    JOptionPane.showMessageDialog(CreateEmployeeAccountDialog.this,
                            "Could not create employee account: " + cause.getMessage(),
                            "Account Creation Failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        btnCreate.setEnabled(!busy);
        btnCreate.setText(busy ? "Creating account and sign-in..." : "Create employee account");
        setCursor(busy ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
                : java.awt.Cursor.getDefaultCursor());
    }
}
