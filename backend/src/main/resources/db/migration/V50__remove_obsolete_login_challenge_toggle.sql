DELETE FROM system_parameter
WHERE parameter_key = 'security.captcha.enabled';

UPDATE system_parameter
SET parameter_value = '101', version = version + 1, updated_by = 0
WHERE parameter_key = 'mobile.android-min-version-code'
  AND CAST(parameter_value AS UNSIGNED) < 101;
