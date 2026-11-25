UPDATE sensor
SET warning_voltage = 2700
WHERE battery_type = 'LI_ION' AND warning_voltage = 2900;
