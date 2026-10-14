-- Preserve every historic value; NULL distinguishes unknown from zero in new legacy sales.
IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('VendaPagamentos') AND name = 'CashGiven' AND is_nullable = 0)
    ALTER TABLE VendaPagamentos ALTER COLUMN CashGiven DECIMAL(18,2) NULL;
IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('VendaPagamentos') AND name = 'ChangeAmount' AND is_nullable = 0)
    ALTER TABLE VendaPagamentos ALTER COLUMN ChangeAmount DECIMAL(18,2) NULL;
