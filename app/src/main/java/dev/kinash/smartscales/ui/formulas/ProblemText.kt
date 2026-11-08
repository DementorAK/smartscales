package dev.kinash.smartscales.ui.formulas

import androidx.annotation.StringRes
import dev.kinash.smartscales.R
import dev.kinash.smartscales.core.formula.ProblemKind

/** The text of a problem in the person's language; its arguments go in as `%1$s`, `%2$s`… */
@get:StringRes
val ProblemKind.text: Int
    get() = when (this) {
        ProblemKind.NOT_OBJECT -> R.string.problem_not_object
        ProblemKind.NO_FORMAT -> R.string.problem_no_format
        ProblemKind.UNSUPPORTED_FORMAT -> R.string.problem_unsupported_format
        ProblemKind.MALFORMED -> R.string.problem_malformed
        ProblemKind.BUILTIN_ID -> R.string.problem_builtin_id
        ProblemKind.SET_ID_TAKEN -> R.string.problem_set_id_taken
        ProblemKind.SET_ID_USED -> R.string.problem_set_id_used
        ProblemKind.ID_RESERVED -> R.string.problem_id_reserved
        ProblemKind.ID_INVALID -> R.string.problem_id_invalid
        ProblemKind.ENGLISH_REQUIRED -> R.string.problem_english_required
        ProblemKind.STANDARD_INPUT -> R.string.problem_standard_input
        ProblemKind.INPUT_UNIT -> R.string.problem_input_unit
        ProblemKind.INPUT_REPLACED -> R.string.problem_input_replaced
        ProblemKind.INPUT_REPLACED_UNIT -> R.string.problem_input_replaced_unit
        ProblemKind.DEFINED_TWICE -> R.string.problem_defined_twice
        ProblemKind.INDICATOR_REPLACED -> R.string.problem_indicator_replaced
        ProblemKind.PATCH_NOTHING -> R.string.problem_patch_nothing
        ProblemKind.PATCHED_TWICE -> R.string.problem_patched_twice
        ProblemKind.HIDE_NOTHING -> R.string.problem_hide_nothing
        ProblemKind.GROUP_NAME_UNUSED -> R.string.problem_group_name_unused
        ProblemKind.PRECISION -> R.string.problem_precision
        ProblemKind.PARAM_NAME -> R.string.problem_param_name
        ProblemKind.PARAM_TWICE -> R.string.problem_param_twice
        ProblemKind.NOT_A_NUMBER -> R.string.problem_not_a_number
        ProblemKind.REQUIRED -> R.string.problem_required
        ProblemKind.ZONE_LEVELS -> R.string.problem_zone_levels
        ProblemKind.ZONE_BOUNDS -> R.string.problem_zone_bounds
        ProblemKind.ZONE_LABELS -> R.string.problem_zone_labels
        ProblemKind.GROUP_IS_INDICATOR -> R.string.problem_group_is_indicator
        ProblemKind.GROUP_IS_INPUT -> R.string.problem_group_is_input
        ProblemKind.INDICATOR_IS_INPUT -> R.string.problem_indicator_is_input
        ProblemKind.PARAM_HIDES_INPUT -> R.string.problem_param_hides_input
        ProblemKind.PARAM_HIDES_INDICATOR -> R.string.problem_param_hides_indicator
        ProblemKind.PARAM_UNUSED -> R.string.problem_param_unused
        ProblemKind.MUST_GIVE_NUMBER -> R.string.problem_must_give_number
        ProblemKind.MUST_GIVE_CONDITION -> R.string.problem_must_give_condition
        ProblemKind.LOOP -> R.string.problem_loop
        ProblemKind.TOO_LONG -> R.string.problem_too_long
        ProblemKind.TOO_DEEP -> R.string.problem_too_deep
        ProblemKind.BAD_NUMBER -> R.string.problem_bad_number
        ProblemKind.UNEXPECTED_CHARACTER -> R.string.problem_unexpected_character
        ProblemKind.EMPTY -> R.string.problem_empty
        ProblemKind.UNEXPECTED -> R.string.problem_unexpected
        ProblemKind.CHAINED_COMPARISON -> R.string.problem_chained_comparison
        ProblemKind.ENDS_EARLY -> R.string.problem_ends_early
        ProblemKind.EXPECTED_BUT_END -> R.string.problem_expected_but_end
        ProblemKind.EXPECTED_BUT -> R.string.problem_expected_but
        ProblemKind.FUNCTION_NOT_CALLED -> R.string.problem_function_not_called
        ProblemKind.UNKNOWN_NAME -> R.string.problem_unknown_name
        ProblemKind.UNKNOWN_FUNCTION -> R.string.problem_unknown_function
        ProblemKind.COMPARE_TYPES -> R.string.problem_compare_types
        ProblemKind.ARGUMENTS_ONE -> R.string.problem_arguments_one
        ProblemKind.ARGUMENTS_EXACTLY -> R.string.problem_arguments_exactly
        ProblemKind.ARGUMENTS_AT_LEAST -> R.string.problem_arguments_at_least
        ProblemKind.ARGUMENTS_RANGE -> R.string.problem_arguments_range
        ProblemKind.IF_ARGUMENTS -> R.string.problem_if_arguments
        ProblemKind.COALESCE_ARGUMENTS -> R.string.problem_coalesce_arguments
        ProblemKind.HAS_ARGUMENTS -> R.string.problem_has_arguments
        ProblemKind.EXPECTED_NUMBER -> R.string.problem_expected_number
        ProblemKind.EXPECTED_CONDITION -> R.string.problem_expected_condition
    }
