CREATE TRIGGER reject_ledger_transaction_update
BEFORE UPDATE ON ledger_transactions
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ledger transactions are immutable';

CREATE TRIGGER reject_ledger_transaction_delete
BEFORE DELETE ON ledger_transactions
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ledger transactions are immutable';

CREATE TRIGGER reject_ledger_entry_update
BEFORE UPDATE ON ledger_entries
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ledger entries are immutable';

CREATE TRIGGER reject_ledger_entry_delete
BEFORE DELETE ON ledger_entries
FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Ledger entries are immutable';
